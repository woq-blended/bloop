// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.io.File
import java.net.URI
import java.util.Optional
import java.util.concurrent.CompletableFuture

import monix.eval.Task
import monix.execution.Scheduler
import sbt.internal.inc.JavaInterfaceUtil.EnrichOption
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import sbt.internal.inc.{Analysis, AnalyzingCompiler, CompileConfiguration, CompilerArguments, MixedAnalyzingCompiler, ScalaInstance}
import sbt.util.{InterfaceUtil, Logger}
import xsbti.{AnalysisCallback, CompileFailed}
import xsbti.compile.{ClassFileManager, CompileOrder, DependencyChanges, IncToolOptions, MultipleOutput, SingleOutput}

/**
 *
 * Defines a high-level compiler after [[sbt.internal.inc.MixedAnalyzingCompiler]], with the
 * exception that this one changes the interface to allow compilation to be task-based and
 * only proceed after external tasks signal it (see `startJavaCompilation` in `compile`).
 *
 * This change is paramount to get pipelined incremental compilation working.
 *
 * @param scalac The Scala compiler (this one takes the concrete implementation, not an interface).
 * @param javac The concrete Java compiler.
 * @param config The compilation configuration.
 * @param logger The logger.
 */
final class BloopHighLevelCompiler(
    scalac: AnalyzingCompiler,
    javac: AnalyzingJavaCompiler,
    config: CompileConfiguration,
    promise: CompletableFuture[Optional[URI]],
    logger: Logger
) {
  private[this] final val setup = config.currentSetup
  private[this] final val classpath = config.classpath.map(_.getAbsoluteFile)

  /**
   * Compile
   *
   * @param sourcesToCompile The source to (incrementally) compile at one time.
   * @param changes The dependency changes detected previously.
   * @param callback The analysis callback from the compiler.
   * @param classfileManager The classfile manager charged with the class files lifecycle.
   * @param startJavaCompilation A task that will end whenever Java compilation can start.
   * @return
   */
  def compile(
      sourcesToCompile: Set[File],
      changes: DependencyChanges,
      callback: AnalysisCallback,
      classfileManager: ClassFileManager,
      startJavaCompilation: Task[Boolean]
  ): Task[Unit] = {
    def timed[T](label: String, log: Logger)(t: => T): T = {
      val start = System.nanoTime
      val result = t
      val elapsed = System.nanoTime - start
      log.debug(label + " took " + (elapsed / 1e6) + " ms")
      result
    }

    val outputDirs = {
      setup.output match {
        case single: SingleOutput => List(single.getOutputDirectory)
        case mult: MultipleOutput => mult.getOutputGroups.iterator.map(_.getOutputDirectory).toList
      }
    }

    outputDirs.foreach { d =>
      if (!d.getPath.endsWith(".jar") && !d.exists())
        sbt.io.IO.createDirectory(d)
    }

    val includedSources = config.sources.filter(sourcesToCompile)
    val (javaSources, scalaSources) = includedSources.partition(_.getName.endsWith(".java"))
    logInputs(logger, javaSources.size, scalaSources.size, outputDirs)

    val compileScala: Task[Unit] = {
      if (scalaSources.isEmpty) Task.now(())
      else {
        val isDotty = ScalaInstance.isDotty(scalac.scalaInstance.actualVersion())
        val sources = if (setup.order == CompileOrder.Mixed) includedSources else scalaSources
        val cargs = new CompilerArguments(scalac.scalaInstance, config.classpathOptions)
        def compileSources(
            sources: Seq[File],
            scalacOptions: Array[String],
            picklepath: Seq[URI]
        ): Unit = {
          val args = cargs.apply(Nil, classpath, None, scalacOptions).toArray
          val normalSetup = isDotty || picklepath.isEmpty
          if (normalSetup) scalac.compile(sources.toArray, changes, args, setup.output, callback, config.reporter, config.cache, logger, config.progress.toOptional)
          else scalac.compileAndSetUpPicklepath(sources.toArray, picklepath.toArray, changes, args, setup.output, callback, config.reporter, config.cache, logger, config.progress.toOptional)
        }

        def compileInParallel: Task[Unit] = {
          val firstCompilation = Task {
            val scalacOptionsFirstPass =
              BloopHighLevelCompiler.prepareOptsForOutlining(setup.options.scalacOptions)
            val args = cargs.apply(Nil, classpath, None, scalacOptionsFirstPass).toArray
            timed("Scala compilation (first pass)", logger) {
              compileSources(sources, scalacOptionsFirstPass, config.picklepath)
            }
          }

          import bloop.monix.Java8Compat.JavaCompletableFutureUtils
          val scalacOptions = setup.options.scalacOptions
          val action = Task.deferFutureAction(s => promise.asScala(s))
          firstCompilation
            .flatMap(_ => action)
            .flatMap { pickleURI =>
              InterfaceUtil.toOption(pickleURI) match {
                case Some(pickleURI) =>
                  val groups: List[Seq[File]] = {
                    val groupSize = scalaSources.size / Runtime.getRuntime().availableProcessors()
                    if (groupSize == 0) List(scalaSources)
                    else scalaSources.grouped(groupSize).toList
                  }

                  Task.gatherUnordered(
                    groups.map { scalaSourceGroup =>
                      Task {
                        timed("Scala compilation (second pass)", logger) {
                          val sourceGroup = {
                            if (setup.order != CompileOrder.Mixed) scalaSourceGroup
                            else scalaSourceGroup ++ javaSources
                          }
                          compileSources(sourceGroup, scalacOptions, List(pickleURI))
                        }
                      }
                    }
                  )
                case None => sys.error("Expecting pickle URI")
              }
            }
            .map(_ => ()) // Just drop the list of units
        }

        def compileSequentially: Task[Unit] = Task {
          val scalacOptions = setup.options.scalacOptions
          val args = cargs.apply(Nil, classpath, None, scalacOptions).toArray
          timed("Scala compilation", logger) {
            compileSources(sources, scalacOptions, config.picklepath)
          }
        }

        compileInParallel
      }
    }

    // Note that we only start Java compilation when the task `startJavaCompilation` signals it
    val compileJava: Task[Unit] = startJavaCompilation.map { _ =>
      if (javaSources.isEmpty) ()
      else {
        timed("Java compilation + analysis", logger) {
          val incToolOptions = IncToolOptions.of(
            Optional.of(classfileManager),
            config.incOptions.useCustomizedFileManager()
          )
          val javaOptions = setup.options.javacOptions.toArray[String]
          try javac.compile(javaSources, javaOptions, setup.output, callback, incToolOptions, config.reporter, logger, config.progress)
          catch {
            case f: CompileFailed =>
              // Intercept and report manually because https://github.com/sbt/zinc/issues/520
              config.reporter.printSummary()
              throw f
          }
        }
      }
    }

    val compilationTask = {
      /* `Mixed` order defaults to `ScalaThenJava` behaviour.
       * See https://github.com/sbt/zinc/issues/234. */
      if (setup.order == CompileOrder.JavaThenScala) {
        compileJava.flatMap(_ => compileScala)
      } else {
        compileScala.flatMap(_ => compileJava)
      }
    }

    compilationTask.map { _ =>
      // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
      if (javaSources.size + scalaSources.size > 0)
        logger.info("Done compiling.")
    }
  }

  // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
  private[this] def logInputs(
      log: Logger,
      javaCount: Int,
      scalaCount: Int,
      outputDirs: Seq[File]
  ): Unit = {
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaCount)
    val javaMsg = Analysis.counted("Java source", "", "s", javaCount)
    val combined = scalaMsg ++ javaMsg
    if (combined.nonEmpty) {
      val targets = outputDirs.map(_.getAbsolutePath).mkString(",")
      log.info(combined.mkString("Compiling ", " and ", s" to $targets ..."))
    }
  }
}

object BloopHighLevelCompiler {
  private val OutlineCompileOptions: Array[String] =
    Array("-Ygen-pickler", "-Youtline", "-Ystop-after:picklergen")
  private val NonFriendlyCompileOptions: Array[String] =
    Array("-Ywarn-dead-code", "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-unused-import")

  def prepareOptsForOutlining(opts: Array[String]): Array[String] = {
    val newOpts = opts.filterNot(o => NonFriendlyCompileOptions.contains(o) || o.startsWith("-Xlint"))
    newOpts ++ OutlineCompileOptions
  }

  def apply(
      config: CompileConfiguration,
      promise: CompletableFuture[Optional[URI]],
      log: Logger
  ): BloopHighLevelCompiler = {
    val (searchClasspath, entry) = MixedAnalyzingCompiler.searchClasspathAndLookup(config)
    val scalaCompiler = config.compiler.asInstanceOf[AnalyzingCompiler]
    val javaCompiler = new AnalyzingJavaCompiler(config.javac, config.classpath, config.compiler.scalaInstance, config.classpathOptions, entry, searchClasspath)
    new BloopHighLevelCompiler(scalaCompiler, javaCompiler, config, promise, log)
  }
}