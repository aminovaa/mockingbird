package ru.tinkoff.tcb.mockingbird.grpc

import io.circe.Json
import io.circe.syntax.KeyOps
import io.grpc.CallOptions
import io.grpc.ManagedChannelBuilder
import mouse.option.*
import scalapb.zio_grpc.RequestContext
import scalapb.zio_grpc.ZManagedChannel
import scalapb.zio_grpc.client.ClientCalls
import zio.Duration
import zio.interop.catz.core.*

import ru.tinkoff.tcb.mockingbird.api.Tracing
import ru.tinkoff.tcb.mockingbird.api.WLD
import ru.tinkoff.tcb.mockingbird.dal.PersistentStateDAO
import ru.tinkoff.tcb.mockingbird.error.StubSearchError
import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.FromGrpcProtoDefinition
import ru.tinkoff.tcb.mockingbird.misc.Renderable.ops.*
import ru.tinkoff.tcb.mockingbird.model.FillResponse
import ru.tinkoff.tcb.mockingbird.model.GProxyResponse
import ru.tinkoff.tcb.mockingbird.model.PersistentState
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.protocol.log.*
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox
import ru.tinkoff.tcb.utils.transformation.json.*

trait GrpcRequestHandler {
  def exec(bytes: Array[Byte]): RIO[WLD & RequestContext, Array[Byte]]
}

class GrpcRequestHandlerImpl(
    stateDAO: PersistentStateDAO[Task],
    stubResolver: GrpcStubResolver,
    implicit val jsSandbox: GraalJsSandbox
) extends GrpcRequestHandler {
  override def exec(bytes: Array[Byte]): RIO[WLD & RequestContext, Array[Byte]] =
    for {
      context <- ZIO.service[RequestContext]
      grpcServiceName = context.methodDescriptor.getFullMethodName
      f               = stubResolver.findStubAndState(grpcServiceName, bytes) _
      _ <- Tracing.update(_.addToPayload("service" -> grpcServiceName))
      (stub, req, stateOp) <- f(Scope.Countdown)
        .filterOrElse(_.isDefined)(f(Scope.Ephemeral).filterOrElse(_.isDefined)(f(Scope.Persistent)))
        .someOrFail(StubSearchError(s"Can't find any stub for $grpcServiceName"))
      _ <- Tracing.update(_.addToPayload("name" -> stub.name))
      seed = stub.seed.map(_.eval.useAsIs)
      state <- ZIO.fromOption(stateOp).orElse(PersistentState.fresh)
      data = Json.obj(
        "req" := req,
        "seed" := seed,
        "state" := state.data
      )
      persist = stub.persist
      _ <- persist
        .cata(spec => stateDAO.upsertBySpec(state.id, spec.fill(data)).map(_.successful), ZIO.succeed(true))
      _ <- persist
        .map(_.keys.map(_.path).filter(_.startsWith("_")).toVector)
        .filter(_.nonEmpty)
        .cata(_.traverse(stateDAO.createIndexForDataField), ZIO.unit)
      responseSchema = stub.responseSchema
      response <- stub.response match {
        case FillResponse(rdata, delay) =>
          ZIO.when(delay.isDefined)(ZIO.sleep(Duration.fromScala(delay.get))) *>
            ZIO.attemptBlocking(responseSchema.parseFromJson(rdata.substitute(data).useAsIs, stub.responseClass))
        case GProxyResponse(endpoint, patch, delay) =>
          for {
            _          <- ZIO.when(delay.isDefined)(ZIO.sleep(Duration.fromScala(delay.get)))
            binaryResp <- proxyCall(endpoint, bytes)
            jsonResp   <- responseSchema.convertMessageToJson(binaryResp, stub.responseClass)
            patchedJsonResp   = jsonResp.patch(data, patch).useAsIs
            patchedBinaryResp = responseSchema.parseFromJson(patchedJsonResp, stub.responseClass)
          } yield patchedBinaryResp
      }
    } yield response

  private def proxyCall(
      endpoint: String,
      bytes: Array[Byte]
  ): RIO[RequestContext, Array[Byte]] = {
    val mc: ZManagedChannel = ZManagedChannel(
      ManagedChannelBuilder.forTarget(endpoint).usePlaintext(),
    )

    ZIO.scoped {
      mc.flatMap { channel =>
        for {
          context <- ZIO.service[RequestContext]
          result <- ClientCalls
            .unaryCall(
              channel,
              Method.byteMethod(context.methodDescriptor.getServiceName, context.methodDescriptor.getBareMethodName),
              CallOptions.DEFAULT,
              context.metadata,
              bytes
            )
        } yield result
      }
    }
  }
}

object GrpcRequestHandlerImpl {
  val live: URLayer[PersistentStateDAO[Task] & GrpcStubResolver & GraalJsSandbox, GrpcRequestHandlerImpl] =
    ZLayer.fromFunction(new GrpcRequestHandlerImpl(_, _, _))
}

object GrpcRequestHandler {
  def exec(bytes: Array[Byte]): RIO[WLD & RequestContext & GrpcRequestHandler, Array[Byte]] =
    for {
      _        <- Tracing.init
      context  <- ZIO.service[RequestContext]
      service  <- ZIO.service[GrpcRequestHandler]
      tracing  <- ZIO.service[Tracing]
      _        <- tracing.fillWithGrpcMetadata(context.metadata)
      _        <- tracing.putToGrpcMetadata(context.responseMetadata)
      response <- service.exec(bytes)
    } yield response
}
