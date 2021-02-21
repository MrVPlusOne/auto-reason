package lambdanet.correctness

import ammonite.ops.Path
import funcdiff.SimpleMath.readObjectFromFile
import lambdanet.{LoadModel, Model, announced}

object NewestModelService {
  val modelDir: Path = LoadModel.newestModelDir
  val modelCachePath: Path = modelDir / "model.serialized"

  lazy val model: Model =
    announced(s"Loading model from $modelCachePath") {
      readObjectFromFile[Model](modelCachePath)
    }
  lazy val service: model.PredictionService =
    model.PredictionService(numOfThreads = 8, predictTopK = Int.MaxValue)
}
