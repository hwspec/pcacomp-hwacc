// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.18"
ThisBuild / version          := "0.4.0"
ThisBuild / organization     := "com.github.kazutomo"
ThisBuild / logLevel := Level.Warn

val chiselVersion = "7.16.0"

// Test / parallelExecution := false

lazy val root = (project in file("."))
  .settings(
    name := "pca-comp",
    resolvers += "central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.16" % "test",
      "com.typesafe.play" %% "play-json" % "2.10.0",
      "io.github.hwspec" %% "garageworks" % "0.4.0-SNAPSHOT",
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
