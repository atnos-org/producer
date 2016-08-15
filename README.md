# producer
Simple generators for Scala

[![Build Status](https://travis-ci.org/atnos-org/producer.png?branch=master)](https://travis-ci.org/atnos-org/producer)
[![Join the chat at https://gitter.im/atnos-org/producer](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/atnos-org/producer?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Producer supports effectful streams where effects are supported by the Eff monad.

It is inspired by the `scalaz-stream` library at least for its API.

## Installation

You add `producer` as an sbt dependency:
```scala
libraryDependencies += "org.atnos" %% "producer" % "1.0.0"

// to write types like Reader[String, ?]
addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")

// to get types like Reader[String, ?] (with more than one type parameter) correctly inferred
addCompilerPlugin("com.milessabin" % "si2712fix-plugin_2.11.8" % "1.2.0")
```

# Contributing

[producer](https://github.com/atnos-org/producer/) is a [Typelevel](http://typelevel.org) project. This means we embrace pure, typeful, functional programming,
and provide a safe and friendly environment for teaching, learning, and contributing as described in the [Typelevel Code of Conduct](http://typelevel.org/conduct.html).

Feel free to open an issue if you notice a bug, have an idea for a feature, or have a question about the code. Pull requests are also gladly accepted.
