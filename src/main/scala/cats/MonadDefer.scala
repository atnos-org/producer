package cats

import simulacrum.typeclass
import cats.Eval.always
import org.atnos.eff.{Eff, safe}
import org.atnos.eff.safe._Safe

/**
 * A [[Monad monad]] that allows for arbitrarily delaying the
 * evaluation of an operation, triggering its execution on each run.
 *
 * @see [[ApplicativeEval]] for capturing effects in an `F[_]`
 *     applicative context, but without the repeating side-effects
 *     requirement.
 */
@typeclass trait MonadDefer[F[_]] extends Monad[F] with ApplicativeEval[F] {
  /**
   * Returns an `F[A]` that evaluates the provided by-name `fa`
   * parameter on each run. In essence it builds an `F[A]` factory.
   */
  def defer[A](fa: => F[A]): F[A] =
    flatten(eval(always(fa)))
}

object MonadDefer extends LowerMonadDeferImplicits {

  implicit def MonadDeferEval: MonadDefer[Eval] = new MonadDefer[Eval] {
    val monad: Monad[Eval] = Eval.catsBimonadForEval

    def pure[A](a: A): Eval[A] =
      monad.pure(a)

    def flatMap[A, B](fa: Eval[A])(f: A => Eval[B]): Eval[B] =
      monad.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => Eval[Either[A, B]]): Eval[B] =
      monad.tailRecM(a)(f)

    def eval[A](a: Eval[A]): Eval[A] =
      a

  }

}

trait LowerMonadDeferImplicits {

  implicit def MonadDeferEffSafe[R :_Safe]: MonadDefer[Eff[R, ?]] = new MonadDefer[Eff[R, ?]] {
    def pure[A](a: A): Eff[R, A] =
      Eff.pure(a)

    def flatMap[A, B](fa: Eff[R, A])(f: A => Eff[R, B]): Eff[R, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[R, Either[A,B]]): Eff[R,B] =
      Eff.EffMonad[R].tailRecM(a)(f)

    def eval[A](a: Eval[A]): Eff[R, A] =
      safe.eval(a)
  }


}
