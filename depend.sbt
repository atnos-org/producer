lazy val effcatsVersion = "2.0.0-RC4"
lazy val specs2Version  = "3.8.4"
lazy val origamiVersion = "2.0.0-20160813102620-cc1f404"

libraryDependencies :=
  effcats ++
  specs2 ++
  origami

resolvers ++= Seq(
    Resolver.sonatypeRepo("releases")
  , Resolver.typesafeRepo("releases")
  , Resolver.url("ambiata-oss", new URL("https://ambiata-oss.s3.amazonaws.com"))(Resolver.ivyStylePatterns))

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")
addCompilerPlugin("com.milessabin" % "si2712fix-plugin_2.11.8" % "1.2.0")

lazy val effcats = Seq(
  "org.atnos" %% "eff-cats" % effcatsVersion)

lazy val origami = Seq(
  "org.atnos" %% "origami-core",
  "org.atnos" %% "origami-lib").map(_ % origamiVersion % "test")

lazy val specs2 = Seq(
    "org.specs2" %% "specs2-core"
  , "org.specs2" %% "specs2-matcher-extra"
  , "org.specs2" %% "specs2-scalacheck"
  , "org.specs2" %% "specs2-cats"
  , "org.specs2" %% "specs2-html"
  , "org.specs2" %% "specs2-junit").map(_ % specs2Version % "test")


