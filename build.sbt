// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.12"
//ThisBuild / scalaVersion     := "2.13.18"
ThisBuild / version          := "0.3.0"
ThisBuild / organization     := "com.github.kazutomo"
ThisBuild / logLevel := Level.Warn

//val chiselVersion = "6.7.0"
val chiselVersion = "7.6.0"

lazy val root = (project in file("."))
  .settings(
    name := "pca-comp",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.16" % "test",
//      "edu.berkeley.cs" %% "chiseltest" % "6.0.0",
      "com.typesafe.play" %% "play-json" % "2.10.0",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
