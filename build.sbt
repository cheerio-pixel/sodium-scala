lazy val root = (project in file(".")).settings(
  name := "sodium-scala.core",
  crossScalaVersions := Seq("2.13.16", "3.6.4"),
  // To make the package for publishing
  organization := "scala.sodium",
  version := "1.0.0",
  scalaVersion := "3.6.4",
  scalacOptions ++= Seq(
    "-deprecation",
    "-explaintypes", // Explain type errors in more detail.
  ),

  libraryDependencies ++= Seq(
    "com.novocode" % "junit-interface" % "0.11" % Test
  ),
)
