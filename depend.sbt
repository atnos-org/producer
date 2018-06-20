lazy val effVersion        = "5.3.0"
lazy val specs2Version     = "4.3.0"
lazy val origamiVersion    = "5.0.1"
lazy val simulacrumVersion = "0.12.0"
lazy val catsVersion       = "1.1.0"

libraryDependencies in Global :=
  eff        ++
  specs2     ++
  origami    ++
  simulacrum ++
  catsFree

resolvers ++= Seq(
    Resolver.sonatypeRepo("releases")
  , Resolver.typesafeRepo("releases")
  , Resolver.url("ambiata-oss", new URL("https://ambiata-oss.s3.amazonaws.com"))(Resolver.ivyStylePatterns))

lazy val eff = Seq(
  "org.atnos" %% "eff" % effVersion)

lazy val origami = Seq(
  "org.atnos" %% "origami-core",
  "org.atnos" %% "origami-lib").map(_ % origamiVersion)

lazy val specs2 = Seq(
    "org.specs2" %% "specs2-core"
  , "org.specs2" %% "specs2-matcher-extra"
  , "org.specs2" %% "specs2-scalacheck"
  , "org.specs2" %% "specs2-html"
  , "org.specs2" %% "specs2-junit").map(_ % specs2Version % "test")

lazy val simulacrum = Seq(
  "com.github.mpilquist" %% "simulacrum" % simulacrumVersion)

lazy val catsFree = Seq(
  "org.typelevel" %% "cats-free" % catsVersion)