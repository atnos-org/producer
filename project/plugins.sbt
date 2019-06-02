addSbtPlugin("com.jsuereth"         % "sbt-pgp"               % "1.1.2")
addSbtPlugin("com.typesafe.sbt"     % "sbt-ghpages"           % "0.6.3")
addSbtPlugin("com.typesafe.sbt"     % "sbt-site"              % "1.4.0")
addSbtPlugin("org.scoverage"        % "sbt-scoverage"         % "1.5.1")
addSbtPlugin("com.typesafe.sbt"     % "sbt-git"               % "1.0.0")
addSbtPlugin("org.xerial.sbt"       % "sbt-sonatype"          % "2.5")
addSbtPlugin("ohnosequences"        % "sbt-github-release"    % "0.7.0")
addSbtPlugin("com.eed3si9n"         % "sbt-buildinfo"         % "0.9.0")

resolvers += "Era7 maven releases" at "http://releases.era7.com.s3.amazonaws.com"
