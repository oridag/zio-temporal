package zio.temporal.internal

import io.temporal.client.BatchRequest
import zio.temporal._
import zio.temporal.signal.ZSignalBuilder
import zio.temporal.workflow.ZWorkflowClient
import scala.reflect.macros.blackbox

class ZSignalMacro(override val c: blackbox.Context) extends InvocationMacroUtils(c) {
  import c.universe._

  private val ZSignalBuilder  = typeOf[ZSignalBuilder].dealias
  private val BatchRequest    = typeOf[BatchRequest].dealias
  private val ZWorkflowClient = typeOf[ZWorkflowClient].dealias

  def signalWithStartBuilderImpl(f: Expr[Unit]): Tree = {
    val tree = f.tree
    val self = getPrefixOf(ZWorkflowClient)

    val invocation = getMethodInvocation(tree)
    val method     = invocation.getMethod(SharedCompileTimeMessages.sgnlMethodShouldntBeExtMethod)
    method.assertSignalMethod()

    val addSignal = addBatchRequestTree(tree)(addTo = None)

    q"""new $ZSignalBuilder($self, $addSignal)"""
  }

  def signalWithStartImpl[A: WeakTypeTag](f: Expr[A]): Tree = {
    val tree       = f.tree
    val invocation = getMethodInvocation(tree)
    val method     = invocation.getMethod(SharedCompileTimeMessages.wfMethodShouldntBeExtMethod)
    method.assertWorkflowMethod()

    val batchRequest = freshTermName("batchRequest")
    val addStart     = addBatchRequestTree(tree)(addTo = Some(batchRequest))
    val javaClient   = freshTermName("javaClient")

    val self      = getPrefixOf(ZSignalBuilder)
    val batchTree = createBatchRequestTree(self, batchRequest, javaClient, addStart)

    q"""
      _root_.zio.temporal.internal.TemporalInteraction.from {
        $batchTree
      }
     """.debugged(SharedCompileTimeMessages.generatedSignalWithStart)
  }

  def signalImpl(f: Expr[Unit]): Tree = {
    val tree       = f.tree
    val invocation = getMethodInvocation(tree)
    assertWorkflow(invocation.instance.tpe)

    val method = invocation.getMethod(SharedCompileTimeMessages.sgnlMethodShouldntBeExtMethod)
    method.assertSignalMethod()
    val signalName = getSignalName(method.symbol)

    q"""${invocation.instance}.__zio_temporal_invokeSignal($signalName, ${invocation.args})"""
      .debugged(SharedCompileTimeMessages.generatedSignal)
  }

  private def addBatchRequestTree(
    f:     Tree
  )(addTo: Option[TermName]
  ): Tree = {
    val batchRequest = freshTermName("batchRequest")
    addTo.fold[Tree](ifEmpty =
      q"""($batchRequest: $BatchRequest) => _root_.zio.temporal.internal.TemporalWorkflowFacade.addToBatchRequest($batchRequest, () => $f)"""
    ) { batchRequest =>
      q"""_root_.zio.temporal.internal.TemporalWorkflowFacade.addToBatchRequest($batchRequest, () => $f)"""
    }
  }

  private def createBatchRequestTree(
    self:         Tree,
    batchRequest: TermName,
    javaClient:   TermName,
    addStart:     Tree
  ): Tree =
    self match {
      // Try to extract AST previously generated by startWith macro.
      // This should avoid instantiation of ZSignalBuilder in runtime by eliminating it from AST
      case Typed(Apply(_, List(client, Function(_, Apply(_, List(_, signalLambda))))), tpe)
          if tpe.tpe =:= ZSignalBuilder =>
        c.untypecheck(
          q"""
           val $javaClient = $client.toJava
           val $batchRequest = $javaClient.newSignalWithStartRequest()
           $addStart
           _root_.zio.temporal.internal.TemporalWorkflowFacade.addToBatchRequest($batchRequest, $signalLambda)
           new _root_.zio.temporal.ZWorkflowExecution($javaClient.signalWithStart($batchRequest))
         """
        )
      // Produce non-optimized tree in case of mismatch
      case _ =>
        c.warning(
          c.enclosingPosition,
          SharedCompileTimeMessages.zsignalBuildNotExtracted(
            self.getClass,
            self.toString
          )
        )
        val builder = freshTermName("builder")
        q"""
          val $builder = $self
          val $javaClient = $builder.__zio_temporal_workflowClient.toJava
          val $batchRequest = $javaClient.newSignalWithStartRequest()
          $builder.__zio_temporal_addSignal($batchRequest)
          $addStart
          new _root_.zio.temporal.ZWorkflowExecution($javaClient.signalWithStart($batchRequest))
         """
    }

  private def getSignalName(method: Symbol): String =
    getAnnotation(method, SignalMethod).children.tail
      .collectFirst { case NamedArgVersionSpecific(_, Literal(Constant(signalName: String))) =>
        signalName
      }
      .getOrElse(method.name.toString)
}
