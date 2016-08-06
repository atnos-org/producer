package org.atnos.generator

private[generator] sealed trait On[+A]
case class One[A](a: A) extends On[A]
case object Done extends On[Nothing]
