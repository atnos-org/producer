enablePlugins(GhpagesPlugin)
enablePlugins(SitePlugin)
enablePlugins(BuildInfoPlugin)

lazy val producer = project.in(file("."))
  .settings(moduleName := "producer")
  .settings(buildSettings)
  .settings(publishSettings)
  .settings(commonSettings)

lazy val buildSettings = Seq(
  organization := "org.atnos",
  scalaVersion := "2.12.6",
  crossScalaVersions := Seq("2.11.11", "2.12.6")
)

def commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value.filter(_ != "-Xfatal-warnings"),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.6")
) ++ warnUnusedImport ++ prompt

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := Function.const(false),
  publishTo := Option("Releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2"),
  sonatypeProfileName := "org.atnos"
) ++ credentialSettings ++ userGuideSettings

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:_",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ypartial-unification",
  "-Xfuture"
)

lazy val userGuideSettings =
  Seq(
    ghpagesNoJekyll := false,
    siteSourceDirectory in makeSite := target.value / "specs2-reports" / "site",
    includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js",
    git.remoteRepo := "git@github.com:atnos-org/producer.git"
  )

lazy val warnUnusedImport = Seq(
  scalacOptions in (Compile, console) ~= {_.filterNot("-Ywarn-unused-import" == _)},
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
)

lazy val credentialSettings = Seq(
  // For Travis CI - see http://www.cakesolutions.net/teamblogs/publishing-artefacts-to-oss-sonatype-nexus-using-sbt-and-travis-ci
  credentials ++= (for {
    username <- Option(System.getenv().get("SONATYPE_USERNAME"))
    password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
  } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq
)

lazy val prompt = shellPrompt in ThisBuild := { state =>
  val name = Project.extract(state).currentRef.project
  (if (name == "producer") "" else name) + "> "
}

