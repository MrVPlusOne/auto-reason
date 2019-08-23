package lambdanet.train

import java.util.Calendar

import lambdanet.{printResult, _}
import java.util.concurrent.ForkJoinPool

import botkop.numsca
import cats.Monoid
import funcdiff.{SimpleMath => SM}
import funcdiff._
import lambdanet.architecture._
import lambdanet.utils.{EventLogger, QLangDisplay, ReportFinish}
import TrainingState._
import botkop.numsca.Tensor
import lambdanet.architecture.LabelEncoder.{
  SegmentedLabelEncoder,
  TrainableLabelEncoder
}
import lambdanet.translation.PredicateGraph.{PNode, PType, ProjNode}
import org.nd4j.linalg.api.buffer.DataType

import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.{
  Await,
  ExecutionContext,
  ExecutionContextExecutorService,
  Future,
  TimeoutException
}
import scala.language.reflectiveCalls

object TrainingLoop extends TrainingLoopTrait {
  val toyMod: Boolean = false
  val onlySeqModel = false
  val useDropout: Boolean = true
  val useOracleForIsLib: Boolean = true // todo: this is only for experiment

  val taskName: String = {
    val flags = Seq(
      "oracle" -> useOracleForIsLib,
      "toy" -> toyMod
    ).map(flag).mkString

    if (onlySeqModel) "large-seqModel"
    else s"two-stage$flags-${TrainingState.iterationNum}"
  }

  def flag(nameValue: (String, Boolean)): String = {
    val (name, value) = nameValue
    if (value) s"-$name" else ""
  }

  import fileLogger.{println, printInfo, printWarning, printResult, announced}

  def scaleLearningRate(epoch: Int): Double = {
    val min = 0.3
    val epochToSlowDown = if (toyMod) 300 else 40
    SimpleMath
      .linearInterpolate(1.0, min)(epoch.toDouble / epochToSlowDown)
      .max(min)
  }

  def main(args: Array[String]): Unit = {
    Tensor.floatingDataType = DataType.DOUBLE
    run(
      maxTrainingEpochs = if (toyMod) 2500 else 500,
      numOfThreads = readThreadNumber()
    ).result()
  }

  case class run(
      maxTrainingEpochs: Int,
      numOfThreads: Int
  ) {

    printInfo(s"Task: $taskName")
    printInfo(s"maxEpochs = $maxTrainingEpochs, threads = $numOfThreads")
    Timeouts.readFromFile()

    def result(): Unit = {
      val (state, pc, logger) = loadTrainingState(resultsDir, fileLogger)
      val architecture = GruArchitecture(state.dimMessage, pc)
      val seqArchitecture =
        SequenceModel.SeqArchitecture(state.dimMessage, pc)
      val dataSet = DataSet.loadDataSet(taskSupport, architecture, toyMod)
      trainOnProjects(dataSet, state, pc, logger, architecture, seqArchitecture)
        .result()
    }

    //noinspection TypeAnnotation
    case class trainOnProjects(
        dataSet: DataSet,
        trainingState: TrainingState,
        pc: ParamCollection,
        logger: EventLogger,
        architecture: NNArchitecture,
        seqArchitecture: SequenceModel.SeqArchitecture
    ) {
      import dataSet._
      import trainingState._

      var isTraining = false

      val labelCoverage =
        TrainableLabelEncoder(
          trainSet,
          coverageGoal = 0.95,
          architecture,
          dropoutProb = 0.1,
          dropoutThreshold = 500
        )

      val labelEncoder =
        SegmentedLabelEncoder(
          trainSet,
          coverageGoal = 0.98,
          architecture,
          dropoutProb = 0.1,
          dropoutThreshold = 1000
        )

      val nameEncoder = {
        SegmentedLabelEncoder(
          trainSet,
          coverageGoal = 0.98,
          architecture,
          dropoutProb = 0.1,
          dropoutThreshold = 1000
        )
//        ConstantLabelEncoder(architecture)
      }

      printResult(s"Label encoder: ${labelEncoder.name}")
      printResult(s"Name encoder: ${nameEncoder.name}")

      printResult(s"NN Architecture: ${architecture.arcName}")
      printResult(s"Single layer consists of: ${architecture.singleLayerModel}")

      def result(): Unit = {
        val saveInterval = if (toyMod) 100 else 10

        (trainingState.epoch0 + 1 to maxTrainingEpochs).foreach { epoch =>
          announced(s"epoch $epoch") {
            handleExceptions(epoch) {
              DebugTime.logTime("test-devSet") {
                testStep(epoch, isTestSet = false)
              }
              trainStep(epoch)
              DebugTime.logTime("test-testSet") {
                testStep(epoch, isTestSet = true)
              }
              if (epoch % saveInterval == 0) {
                saveTraining(epoch, s"epoch$epoch")
              }
            }
          }
        }

        saveTraining(maxTrainingEpochs, "finished")
        emailService.sendMail(emailService.userEmail)(
          s"TypingNet: Training finished on $machineName!",
          "Training finished!"
        )
      }

//      def logMaximalTestAcc() = {
//        import cats.implicits._
//        val maxAcc = testSet
//          .foldMap(
//            _.fseAcc.maximalAcc()
//          )
//          .pipe(toAccuracy)
//        logger.logString("test-maxAcc", 0, maxAcc.toString)
//      }

      val (machineName, emailService) = ReportFinish.readEmailInfo(taskName)
      private def handleExceptions(epoch: Int)(f: => Unit): Unit = {
        try f
        catch {
          case ex: Throwable =>
            val isTimeout = ex.isInstanceOf[TimeoutException]
            val errorName = if (isTimeout) "timeout" else "stopped"
            emailService.sendMail(emailService.userEmail)(
              s"TypingNet: $errorName on $machineName at epoch $epoch",
              s"Details:\n" + ex.getMessage
            )
            if (isTimeout && Timeouts.restartOnTimeout) {
              printWarning(
                "Timeout... training restarted (skip one training epoch)..."
              )
            } else {
              if (!ex.isInstanceOf[StopException]) {
                saveTraining(epoch, "error-save", skipTest = true)
              }
              throw ex
            }
        }
      }

      val random = new util.Random(2)

      def trainStep(epoch: Int): Unit = {
        isTraining = true

        DebugTime.logTime("GC") {
          System.gc()
        }
        val startTime = System.nanoTime()
        val stats = random.shuffle(trainSet).zipWithIndex.map {
          case (datum, i) =>
            import Console.{GREEN, BLUE}
            announced(
              s"$GREEN[epoch $epoch](progress: ${i + 1}/${trainSet.size})$BLUE train on $datum"
            ) {
//              println(DebugTime.show)
              checkShouldStop(epoch)
              architecture.dropoutStorage = Some(new ParamCollection())
              for {
                (loss, fwd, _) <- selectForward(datum).tap(
                  _.foreach(r => printResult(r._2))
                )
                _ = checkShouldStop(epoch)
              } yield {
                checkShouldStop(epoch)
                def optimize(loss: CompNode) = {
                  val factor = fwd.loss.count.toDouble / avgAnnotations
                  optimizer.minimize(
                    loss * factor,
                    pc.allParams,
                    backPropInParallel =
                      Some(parallelCtx -> Timeouts.optimizationTimeout),
                    gradientTransform = _.clipNorm(2 * factor),
                    scaleLearningRate = scaleLearningRate(epoch)
                  )
                }

                val gradInfo = limitTimeOpt(
                  s"optimization: $datum",
                  Timeouts.optimizationTimeout
                ) {
                  announced("optimization") {
                    val stats = DebugTime.logTime("optimization") {
                      optimize(loss)
                    }
                    calcGradInfo(stats)
                  }
                }.toVector

                (fwd, gradInfo, datum)
              }
            }
        }

        import cats.implicits._
        val (fws, gs, data) = stats.flatMap(_.toVector).unzip3

        fws.combineAll.tap {
          case ForwardResult(
              loss,
              libAcc,
              projAcc,
              confMat,
              categoricalAcc
              ) =>
            logger.logScalar("loss", epoch, toAccuracyD(loss))
            logger.logScalar("libAcc", epoch, toAccuracy(libAcc))
            logger.logScalar("projAcc", epoch, toAccuracy(projAcc))
            logger.logConfusionMatrix("confusionMat", epoch, confMat.value, 2)
            logAccuracyDetails(data zip fws, epoch)

            logger.logString("typeAcc", epoch, typeAccString(categoricalAcc))
        }

        val gradInfo = gs.combineAll
        gradInfo.unzip3.tap {
          case (grads, transformed, deltas) =>
            logger.logScalar("gradient", epoch, grads.sum)
            logger.logScalar("clippedGradient", epoch, transformed.sum)
            logger.logScalar("paramDelta", epoch, deltas.sum)
        }

        val timeInSec = (System.nanoTime() - startTime).toDouble / 1e9
        logger.logScalar("iter-time", epoch, timeInSec)

        println(DebugTime.show)
      }

      private def typeAccString(accs: Map[PType, Counted[Correct]]): String = {
        val (tys, counts) = accs.toVector.sortBy { c =>
          -c._2.count
        }.unzip
        val typeStr = tys
          .map(t => SM.wrapInQuotes(t.showSimple))
          .mkString("{", ",", "}")
        val countStr = counts
          .map(c => s"{${c.count}, ${c.value}}")
          .mkString("{", ",", "}")
        s"{$typeStr,$countStr}"
      }

      private def logAccuracyDetails(
          stats: Vector[(Datum, ForwardResult)],
          epoch: Int
      ) = {
        import cats.implicits._
        val str = stats
          .map {
            case (d, f) =>
              val size = d.predictor.graph.predicates.size
              val acc = toAccuracy(
                f.libCorrect.combine(f.projCorrect)
              )
              val name = d.projectName
              s"""{$size, $acc, "$name"}"""
          }
          .mkString("{", ",", "}")
        logger.logString("accuracy-distr", epoch, str)
      }

      def testStep(epoch: Int, isTestSet: Boolean): Unit = {
        val dataSetName = if (isTestSet) "test" else "dev"
        val dataSet = if (isTestSet) testSet else devSet
        if ((epoch - 1) % 3 == 0) announced(s"test on $dataSetName set") {
          import cats.implicits._
          architecture.dropoutStorage = None
          isTraining = false

          val (stat, fse1Acc) = dataSet.flatMap { datum =>
            checkShouldStop(epoch)
            announced(s"test on $datum") {
              selectForward(datum).map {
                case (_, fwd, pred) =>
                  val (fse1, _, _) = datum.fseAcc
                    .countTopNCorrect(
                      1,
                      pred.mapValuesNow(Vector(_)),
                      onlyCountInSpaceTypes = true
                    )
                  (fwd, fse1)
              }.toVector
            }
          }.combineAll

          import stat.{libCorrect, projCorrect, confusionMatrix, categoricalAcc}
          logger.logScalar(s"$dataSetName-loss", epoch, toAccuracyD(stat.loss))

          logger
            .logScalar(s"$dataSetName-libAcc", epoch, toAccuracy(libCorrect))
          logger
            .logScalar(s"$dataSetName-projAcc", epoch, toAccuracy(projCorrect))
          logger.logConfusionMatrix(
            s"$dataSetName-confusionMat",
            epoch,
            confusionMatrix.value,
            2
          )
          logger.logScalar(s"$dataSetName-fse-top1", epoch, toAccuracy(fse1Acc))
          logger.logString(
            s"$dataSetName-typeAcc",
            epoch,
            typeAccString(categoricalAcc)
          )
        }
      }

      def calcGradInfo(stats: Optimizer.OptimizeStats) = {
        def meanSquaredNorm(gs: Iterable[Gradient]) = {
          import numsca._
          import cats.implicits._
          val combined = gs.toVector.map { g =>
            val t = g.toTensor()
            Counted(t.elements.toInt, sum(square(t)))
          }.combineAll
          math.sqrt(combined.value / nonZero(combined.count))
        }

        val grads = meanSquaredNorm(stats.gradients.values)
        val transformed = meanSquaredNorm(stats.transformedGrads.values)
        val deltas = meanSquaredNorm(stats.deltas.values)
        (grads, transformed, deltas)
      }

      val lossModel: LossModel = LossModel.NormalLoss
        .tap(m => printResult(s"loss model: ${m.name}"))

      private def selectForward(data: Datum) = {
        if (onlySeqModel) seqForward(data)
        else forward(data)
      }

      /** Forward propagation for the sequential model */
      private def seqForward(
          datum: Datum
      ): Option[(Loss, ForwardResult, Map[PNode, PType])] = {
        def result = {
          val predictor = datum.seqPredictor
          val predSpace = predictor.predSpace
          // the logits for very iterations
          val nodes = datum.nodesToPredict.map { _.n }
          val logits = announced("run seq predictor") {
            predictor.run(
              seqArchitecture,
              nameEncoder,
              nodes,
              nameDropout = useDropout && isTraining
            )
          }

          val nonGenerifyIt = DataSet.nonGenerify(predictor.libDefs)

          val groundTruths = nodes.map {
            case n if n.fromLib =>
              nonGenerifyIt(predictor.libDefs.nodeMapping(n).get)
            case n if n.fromProject =>
              datum.annotations(ProjNode(n))
          }

          val targets = groundTruths.map(predSpace.indexOfType)
          val nodeDistances = nodes.map(_.pipe(datum.distanceToConsts))

          val (libCounts, projCounts, confMat, typeAccs) =
            announced("compute training accuracy") {
              analyzeDecoding(
                logits,
                groundTruths,
                predSpace,
                nodeDistances
              )
            }

          val loss = logits.toLoss(targets)

          val totalCount = libCounts.count + projCounts.count
          val fwd = ForwardResult(
            Counted(totalCount, loss.value.squeeze() * totalCount),
            libCounts,
            projCounts,
            confMat,
            typeAccs
          )

          val predictions: Map[PNode, PType] = {
            val predVec = logits.topPredictions.map { predSpace.typeVector }
            nodes.zip(predVec).toMap
          }

          (loss, fwd, predictions)
        }

        limitTimeOpt(s"forward: $datum", Timeouts.forwardTimeout) {
          DebugTime.logTime("seqForward") { result }
        }
      }
      private def forward(
          datum: Datum
      ): Option[(Loss, ForwardResult, Map[PNode, PType])] =
        limitTimeOpt(s"forward: $datum", Timeouts.forwardTimeout) {
          import datum._

          val shouldDropout = useDropout && isTraining

          val predSpace = predictor.predictionSpace

          val groundTruths = nodesToPredict.map(annotations)
          val targets = groundTruths.map(predSpace.indexOfType)
          val isLibOracle =
            if (useOracleForIsLib) Some(targets.map(predSpace.isLibType))
            else None
          val nodeDistances = nodesToPredict.map(_.n.pipe(distanceToConsts))

          // the probability for very iterations
          val decodingVec = announced("run predictor") {
            predictor
              .run(
                architecture,
                nodesToPredict,
                architecture.initialEmbedding,
                iterationNum,
                nodeForAny,
                labelEncoder,
                labelCoverage.isLibLabel,
                nameEncoder,
                shouldDropout,
                isTraining,
                isLibOracle
              )
              .result
          }
          val decoding = decodingVec.last

          val (libCounts, projCounts, confMat, typeAccs) =
            announced("compute training accuracy") {
              analyzeDecoding(
                decoding,
                groundTruths,
                predSpace,
                nodeDistances
              )
            }

          val loss = lossModel.predictionLoss(
            predictor.parallelize(decodingVec).map(_.toLoss(targets))
          )

          val totalCount = libCounts.count + projCounts.count
          val fwd = ForwardResult(
            Counted(totalCount, loss.value.squeeze() * totalCount),
            libCounts,
            projCounts,
            confMat,
            typeAccs
          )

          val predictions: Map[PNode, PType] = {
            val predVec = decoding.topPredictions.map { predSpace.typeVector }
            nodesToPredict.map(_.n).zip(predVec).toMap
          }

          (loss, fwd, predictions)
        }

      @throws[TimeoutException]
      private def limitTime[A](timeLimit: Timeouts.Duration)(f: => A): A = {
        val exec = scala.concurrent.ExecutionContext.global
        Await.result(Future(f)(exec), timeLimit)
      }

      private def limitTimeOpt[A](
          name: String,
          timeLimit: Timeouts.Duration
      )(f: => A): Option[A] = {
        try {
          Some(limitTime(timeLimit)(f))
        } catch {
          case _: TimeoutException =>
            val msg = s"$name exceeded time limit $timeLimit."
            printWarning(msg)
            emailService.atFirstTime {
              emailService.sendMail(emailService.userEmail)(
                s"TypingNet: timeout on $machineName during $name",
                s"Details:\n" + msg
              )
            }
            None
        }
      }

      import ammonite.ops._

      private def saveTraining(
          epoch: Int,
          dirName: String,
          skipTest: Boolean = false
      ): Unit = {
        isTraining = false
        architecture.dropoutStorage = None

        announced(s"save training to $dirName") {
          val saveDir = resultsDir / "saved" / dirName
          if (!exists(saveDir)) {
            mkdir(saveDir)
          }
          val savePath = saveDir / "state.serialized"
          TrainingState(epoch, dimMessage, iterationNum, optimizer)
            .saveToFile(savePath)
          pc.saveToFile(saveDir / "params.serialized")
          val currentLogFile = resultsDir / "log.txt"
          if (exists(currentLogFile)) {
            cp.over(currentLogFile, saveDir / "log.txt")
          }

          if (testSet.isEmpty || skipTest)
            return

          val predSpace = testSet.head.predictor.predictionSpace
          import cats.implicits._

          var progress = 0
          val (right, wrong) = testSet.flatMap { datum =>
            checkShouldStop(epoch)
            announced(
              s"(progress: ${progress.tap(_ => progress += 1)}) test on $datum"
            ) {
              selectForward(datum).map {
                case (_, fwd, pred) =>
                  DebugTime.logTime("printQSource") {
                    QLangDisplay.renderProjectToDirectory(
                      datum.projectName.toString,
                      datum.qModules,
                      pred,
                      predSpace.allTypes
                    )(saveDir / "predictions")
                  }
                  val (_, rightSet, wrongSet) = datum.fseAcc.countTopNCorrect(
                    1,
                    pred.mapValuesNow(Vector(_)),
                    onlyCountInSpaceTypes = true
                  )
                  val Seq(x, y) = Seq(rightSet, wrongSet).map { set =>
                    set.map { n =>
                      val t = datum.annotations(ProjNode(n))
                      (n, t, datum.projectName.toString)
                    }
                  }
                  (x, y)
              }.toVector
            }
          }.combineAll

          QLangDisplay.renderPredictionIndexToDir(
            right,
            wrong,
            saveDir,
            sourcePath = "predictions"
          )

          val dateTime = Calendar.getInstance().getTime
          write.over(saveDir / "time.txt", dateTime.toString)
        }
      }

      @throws[StopException]
      private def checkShouldStop(epoch: Int): Unit = {
        if (TrainingControl(resultsDir).shouldStop(consumeFile = true)) {
          saveTraining(epoch, s"stopped-epoch$epoch")
          throw StopException("Stopped by 'stop.txt'.")
        }
      }

      private def analyzeDecoding(
          results: DecodingResult,
          groundTruths: Vector[PType],
          predictionSpace: PredictionSpace,
          nodeDistances: Vector[Int]
      ): (
          Counted[LibCorrect],
          Counted[ProjCorrect],
          Counted[ConfusionMatrix],
          Map[PType, Counted[Correct]]
      ) = {
        val predictions = results.topPredictions
        val targets = groundTruths.map(predictionSpace.indexOfType)
        val truthValues = predictions.zip(targets).map { case (x, y) => x == y }
        val targetFromLibrary = groundTruths.map { _.madeFromLibTypes }
        val zipped = targetFromLibrary.zip(truthValues)
        val libCorrect = zipped.collect {
          case (true, true) => ()
        }.length
        val projCorrect = zipped.collect {
          case (false, true) => ()
        }.length
        val libCounts = Counted(targetFromLibrary.count(identity), libCorrect)
        val projCounts = Counted(targetFromLibrary.count(!_), projCorrect)

        val confMat = {
          def toCat(isLibType: Boolean): Int = if (isLibType) 0 else 1
          val predictionCats = predictions.map { i =>
            toCat(predictionSpace.isLibType(i))
          }
          val truthCats = targetFromLibrary.map(toCat)
          val mat = confusionMatrix(predictionCats, truthCats, categories = 2)
          Counted(predictionCats.length, mat)
        }

        val typeAccs =
          groundTruths.zip(truthValues).groupBy(_._1).mapValuesNow { bools =>
            Counted(bools.length, bools.count(_._2))
          }

        (libCounts, projCounts, confMat, typeAccs)
      }

      private val avgAnnotations =
        SM.mean(trainSet.map(_.annotations.size.toDouble))
    }

    val taskSupport: Option[ForkJoinTaskSupport] =
      if (numOfThreads == 1) None
      else Some(new ForkJoinTaskSupport(new ForkJoinPool(numOfThreads)))
    val parallelCtx: ExecutionContextExecutorService = {
      import ExecutionContext.fromExecutorService
      fromExecutorService(new ForkJoinPool(numOfThreads))
    }
  }

  private case class ForwardResult(
      loss: Counted[Double],
      libCorrect: Counted[LibCorrect],
      projCorrect: Counted[ProjCorrect],
      confusionMatrix: Counted[ConfusionMatrix],
      categoricalAcc: Map[PType, Counted[Correct]]
  ) {
    override def toString: String = {
      s"forward result: {loss: ${toAccuracyD(loss)}, " +
        s"lib acc: ${toAccuracy(libCorrect)} (${libCorrect.count} nodes), " +
        s"proj acc: ${toAccuracy(projCorrect)} (${projCorrect.count} nodes)}"
    }
  }

  private implicit val forwardResultMonoid: Monoid[ForwardResult] =
    new Monoid[ForwardResult] {
      import Counted.zero
      import cats.implicits._

      def empty: ForwardResult =
        ForwardResult(zero(0), zero(0), zero(0), zero(Map()), Map())

      def combine(x: ForwardResult, y: ForwardResult): ForwardResult = {
        val z = ForwardResult.unapply(x).get |+| ForwardResult
          .unapply(y)
          .get
        (ForwardResult.apply _).tupled(z)
      }
    }

}
