package com.yumusoft.lstmrecommender

import java.io.File

import org.deeplearning4j.eval.Evaluation
import org.deeplearning4j.nn.api.OptimizationAlgorithm
import org.deeplearning4j.nn.api.layers.IOutputLayer
import org.deeplearning4j.nn.conf.graph.rnn.{DuplicateToTimeSeriesVertex, LastTimeStepVertex}
import org.deeplearning4j.nn.conf.inputs.InputType
import org.deeplearning4j.nn.conf.{NeuralNetConfiguration, Updater}
import org.deeplearning4j.nn.conf.layers._
import org.deeplearning4j.nn.graph.ComputationGraph
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.nn.weights.WeightInit
import org.deeplearning4j.optimize.listeners.ScoreIterationListener
import org.deeplearning4j.util.ModelSerializer
import org.nd4j.linalg.activations.Activation
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator
import org.nd4j.linalg.lossfunctions.LossFunctions
import org.slf4j.LoggerFactory
import scopt.OptionParser

case class TrainConfig(
  input: File = null,
  modelName: String = "",
  nEpochs: Int = 1,
  hiddenSize: Int = 128,
  count: Int = 100
)

object TrainConfig {
  val parser = new OptionParser[TrainConfig]("Train") {
    head("lstmrecommender Train", "1.0")

    opt[File]('i', "input")
      .required()
      .valueName("<dir>")
      .action( (x, c) => c.copy(input = x) )
      .text("The directory with training data.")

    opt[Int]('e', "epoch")
      .action( (x, c) => c.copy(nEpochs = x) )
      .text("Number of times to go over whole training set.")

    opt[Int]('h', "hidden")
      .action( (x, c) => c.copy(hiddenSize = x) )
      .text("The size of the hidden layers.")

    opt[String]('o', "output")
      .required()
      .valueName("<modelName>")
      .action( (x, c) => c.copy(modelName = x) )
      .text("Name of trained model file.")

    opt[Int]('c', "count")
      .valueName("<count>")
      .action( (x, c) => c.copy(count = x) )
      .text("Number of examples to train on. 0.8 split")
  }

  def parse(args: Array[String]): Option[TrainConfig] = {
    parser.parse(args, TrainConfig())
  }
}

object Train {
  private val log = LoggerFactory.getLogger(getClass)

  private def embedding(in: Int, out: Int): EmbeddingLayer =
    new EmbeddingLayer.Builder()
      .nIn(in)
      .nOut(out)
      .build()

  private def dense(in: Int, out: Int): DenseLayer =
    new DenseLayer.Builder()
      .nIn(in)
      .nOut(out)
      .activation(Activation.LEAKYRELU)
      .build()

  private def lstm(nIn: Int, size: Int): GravesLSTM =
    new GravesLSTM.Builder()
      .nIn(nIn)
      .nOut(size)
      .activation(Activation.SOFTSIGN)
      .build()

  private def output(nIn: Int, nOut: Int): RnnOutputLayer =
    new RnnOutputLayer.Builder()
      .nIn(nIn)
      .nOut(nOut)
      .activation(Activation.SOFTMAX)
      .lossFunction(LossFunctions.LossFunction.MCXENT)
      .build()

  private def net(itemTypeCount: Int, countryTypeCount: Int, hiddenSize: Int) = new NeuralNetConfiguration.Builder()
    .weightInit(WeightInit.XAVIER)
    .learningRate(0.01)
    .updater(Updater.RMSPROP)
    .rmsDecay(0.95)
    .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
    .iterations(1)
    .seed(42)
    .graphBuilder()
    .addInputs("itemIn", "countryIn")
    .setInputTypes(InputType.recurrent(itemTypeCount), InputType.recurrent(countryTypeCount))
    .addLayer("embed", embedding(itemTypeCount, 100), "itemIn")
    .addLayer("country", embedding(countryTypeCount, 3), "countryIn")
    .addLayer("lstm1", lstm(100 + 3, hiddenSize), "embed", "country")
    .addLayer("dense", dense(hiddenSize, hiddenSize), "lstm1")
    .addLayer("labelOut", output(hiddenSize, itemTypeCount) , "dense")
    .setOutputs("labelOut")
    .build()

  def main(args: Array[String]): Unit = {
    TrainConfig.parse(args) match {
      case Some(config) =>
        log.info("Starting training")

        train(config)

        log.info("Training finished.")
      case _ =>
        log.error("Invalid arguments.")
    }
  }

  private def train(c: TrainConfig): Unit = {
    val sessionsDir = new File(c.input.getAbsolutePath + "/sessions/")
    val itemMapFile = new File(c.input.getAbsolutePath + "/items.csv")
    val countryMapFile = new File(c.input.getAbsolutePath + "/countries.csv")

    val (numClasses, numCountries, trainData) = DataIterators.onlineRetailCsv(
      sessionsDir,
      itemMapFile,
      countryMapFile,
      1,
      (c.count * 0.8).toInt
    )
    val (_, _, testData) = DataIterators.onlineRetailCsv(
      sessionsDir,
      itemMapFile,
      countryMapFile,
      (c.count * 0.8 + 1).toInt,
      c.count
    )

    log.info("Data Loaded")

    val conf = net(numClasses, numCountries, c.hiddenSize)
    val model = new ComputationGraph(conf)
    model.init()

    model.setListeners(new ScoreIterationListener(1))

    for (i <- 0 until c.nEpochs) {
      log.info(s"Starting epoch $i of ${c.nEpochs}")
      model.fit(trainData)

      log.info(s"Finished epoch $i")
      trainData.reset()
    }

    
    val eval = evaluate(model, testData)
    log.info(eval.stats())

    ModelSerializer.writeModel(model, c.modelName, true)

    log.info(s"Model saved to: ${c.modelName}")
  }

  def evaluate(model: ComputationGraph, iterator: MultiDataSetIterator, topN: Int = 20): Evaluation = {
    val e = new Evaluation(null, topN)

    while (iterator.hasNext) {
      val next = iterator.next()
      val features = next.getFeatures
      val labels = next.getLabels
      var out = model.output(false, features :_*)
      e.evalTimeSeries(labels(0), out(0))
    }

    e
  }
}
