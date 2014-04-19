package scalaz.stream2


import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream2.Process._
import scalaz.stream2.ReceiveY._
import scalaz.stream2.Util._
import scalaz.{-\/, \/-, \/}


object wye {

  /**
   * A `Wye` which emits values from its right branch, but allows up to `n`
   * elements from the left branch to enqueue unanswered before blocking
   * on the right branch.
   */
  def boundedQueue[I](n: Int): Wye[Any,I,I] =
    yipWithL(n)((i,i2) => i2)

  /**
   * After each input, dynamically determine whether to read from the left, right, or both,
   * for the subsequent input, using the provided functions `f` and `g`. The returned
   * `Wye` begins by reading from the left side and is left-biased--if a read of both branches
   * returns a `These(x,y)`, it uses the signal generated by `f` for its next step.
   */
  def dynamic[I,I2](f: I => wye.Request, g: I2 => wye.Request): Wye[I,I2,ReceiveY[I,I2]] = {
    import wye.Request._
    def go(signal: wye.Request): Wye[I,I2,ReceiveY[I,I2]] = signal match {
      case L => awaitL[I].flatMap { i => emit(ReceiveL(i)) fby go(f(i)) }
      case R => awaitR[I2].flatMap { i2 => emit(ReceiveR(i2)) fby go(g(i2)) }
      case Both => awaitBoth[I,I2].flatMap {
        case t@ReceiveL(i) => emit(t) fby go(f(i))
        case t@ReceiveR(i2) => emit(t) fby go(g(i2))
        case _ => go(signal)
      }
    }
    go(L)
  }

  /**
   * A `Wye` which echoes the right branch while draining the left,
   * taking care to make sure that the left branch is never more
   * than `maxUnacknowledged` behind the right. For example:
   * `src.connect(snk)(observe(10))` will output the the same thing
   * as `src`, but will as a side effect direct output to `snk`,
   * blocking on `snk` if more than 10 elements have enqueued
   * without a response.
   */
  def drainL[I](maxUnacknowledged: Int): Wye[Any,I,I] =
    wye.flip(drainR(maxUnacknowledged))

  /**
   * A `Wye` which echoes the left branch while draining the right,
   * taking care to make sure that the right branch is never more
   * than `maxUnacknowledged` behind the left. For example:
   * `src.connect(snk)(observe(10))` will output the the same thing
   * as `src`, but will as a side effect direct output to `snk`,
   * blocking on `snk` if more than 10 elements have enqueued
   * without a response.
   */
  def drainR[I](maxUnacknowledged: Int): Wye[I,Any,I] =
    yipWithL[I,Any,I](maxUnacknowledged)((i,i2) => i)

  /**
   * Invokes `dynamic` with `I == I2`, and produces a single `I` output. Output is
   * left-biased: if a `These(i1,i2)` is emitted, this is translated to an
   * `emitSeq(List(i1,i2))`.
   */
  def dynamic1[I](f: I => wye.Request): Wye[I,I,I] =
    dynamic(f, f).flatMap {
      case ReceiveL(i) => emit(i)
      case ReceiveR(i) => emit(i)
      case HaltOne(rsn) => fail(rsn)
    }

  /**
   * Nondeterminstic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   */
  def either[I,I2]: Wye[I,I2,I \/ I2] = {
    def go: Wye[I,I2,I \/ I2] =
      receiveBoth[I,I2,I \/ I2]({
        case ReceiveL(i) => emit(left(i)) fby go
        case ReceiveR(i) => emit(right(i)) fby go
        case HaltL(rsn) =>  awaitR[I2].map(right).repeat.causedBy(rsn)
        case HaltR(rsn) =>  awaitL[I].map(left).repeat.causedBy(rsn)
      })
    go
  }

  /**
   * Continuous wye, that first reads from Left to get `A`,
   * Then when `A` is not available it reads from R echoing any `A` that was received from Left
   * Will halt once any of the sides halt
   */
  def echoLeft[A]: Wye[A, Any, A] = {
    def go(a: A): Wye[A, Any, A] =
      receiveBoth({
        case ReceiveL(l)  => emit(l) fby go(l)
        case ReceiveR(_)  => emit(a) fby go(a)
        case HaltOne(rsn) => fail(rsn)
      })
    awaitL[A].flatMap(s => emit(s) fby go(s))
  }

  /**
   * Let through the right branch as long as the left branch is `false`,
   * listening asynchronously for the left branch to become `true`.
   * This halts as soon as the right or left branch halts.
   */
  def interrupt[I]: Wye[Boolean, I, I] = {
    def go[I]: Wye[Boolean, I, I] =
      awaitBoth[Boolean, I].flatMap {
        case ReceiveR(None) => halt
        case ReceiveR(i)    => emit(i) ++ go
        case ReceiveL(kill) => if (kill) halt else go
        case HaltOne(e)     => fail(e)
      }
    go
  }


  /**
   * Non-deterministic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   *
   * Will terminate once both sides terminate.
   */
  def merge[I]: Wye[I,I,I] = {
    def go: Wye[I,I,I] =
      receiveBoth[I,I,I]({
        case ReceiveL(i) => emit(i) fby go
        case ReceiveR(i) => emit(i) fby go
        case HaltL(rsn) => awaitR.repeat.causedBy(rsn)
        case HaltR(rsn) => awaitL.repeat.causedBy(rsn)
      })
    go
  }

  /**
   * Like `merge`, but terminates whenever one side terminate.
   */
  def mergeHaltBoth[I]: Wye[I,I,I] = {
    def go: Wye[I,I,I] =
      receiveBoth[I,I,I]({
        case ReceiveL(i) => emit(i) fby go
        case ReceiveR(i) => emit(i) fby go
        case HaltOne(rsn) => Halt(rsn)
      })
    go
  }

  /**
   * Like `merge`, but terminates whenever left side terminates.
   * use `flip` to reverse this for the right side
   */
  def mergeHaltL[I]: Wye[I,I,I] = {
    def go: Wye[I,I,I] =
      receiveBoth[I,I,I]({
        case ReceiveL(i) => emit(i) fby go
        case ReceiveR(i) => emit(i) fby go
        case HaltL(rsn) => Halt(rsn)
        case HaltR(rsn) => awaitL.repeat.causedBy(rsn)
      })
    go
  }

  /**
   * Like `merge`, but terminates whenever right side terminates
   */
  def mergeHaltR[I]: Wye[I,I,I] =
    wye.flip(mergeHaltL)

  /**
   * A `Wye` which blocks on the right side when either
   *   a) the age of the oldest unanswered element from the left size exceeds the given duration, or
   *   b) the number of unanswered elements from the left exceeds `maxSize`.
   */
  def timedQueue[I](d: Duration, maxSize: Int = Int.MaxValue): Wye[Duration,I,I] = {
    def go(q: Vector[Duration]): Wye[Duration,I,I] =
      awaitBoth[Duration,I].flatMap {
        case ReceiveL(d2) =>
          if (q.size >= maxSize || (d2 - q.headOption.getOrElse(d2) > d))
            awaitR[I].flatMap(i => emit(i) fby go(q.drop(1)))
          else
            go(q :+ d2)
        case ReceiveR(i) => emit(i) fby (go(q.drop(1)))
        case _ => go(q)
      }
    go(Vector())
  }


  /**
   * `Wye` which repeatedly awaits both branches, emitting any values
   * received from the right. Useful in conjunction with `connect`,
   * for instance `src.connect(snk)(unboundedQueue)`
   */
  def unboundedQueue[I]: Wye[Any,I,I] =
    awaitBoth[Any,I].flatMap {
      case ReceiveL(_) => halt
      case ReceiveR(i) => emit(i) fby unboundedQueue
      case _ => unboundedQueue
    }


  /** Nondeterministic version of `zip` which requests both sides in parallel. */
  def yip[I,I2]: Wye[I,I2,(I,I2)] = yipWith((_,_))

  /**
   * Left-biased, buffered version of `yip`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left.
   */
  def yipL[I,I2](n: Int): Wye[I,I2,(I,I2)] =
    yipWithL(n)((_,_))

  /** Nondeterministic version of `zipWith` which requests both sides in parallel. */
  def yipWith[I,I2,O](f: (I,I2) => O): Wye[I,I2,O] =
    awaitBoth[I,I2].flatMap {
      case ReceiveL(i) => awaitR[I2].flatMap(i2 => emit(f(i,i2)) ++ yipWith(f))
      case ReceiveR(i2) => awaitL[I].flatMap(i => emit(f(i,i2)) ++ yipWith(f))
      case _ => halt
    }

  /**
   * Left-biased, buffered version of `yipWith`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left.
   */
  def yipWithL[I,O,O2](n: Int)(f: (I,O) => O2): Wye[I,O,O2] = {
    def go(buf: Vector[I]): Wye[I,O,O2] =
      if (buf.size > n) awaitR[O].flatMap { o =>
        emit(f(buf.head,o)) ++ go(buf.tail)
      }
      else if (buf.isEmpty) awaitL[I].flatMap { i => go(buf :+ i) }
      else awaitBoth[I,O].flatMap {
        case ReceiveL(i) => go(buf :+ i)
        case ReceiveR(o) => emit(f(buf.head,o)) ++ go(buf.tail)
        case _ => halt
      }
    go(Vector())
  }

  //////////////////////////////////////////////////////////////////////
  // Helper combinator functions, useful when working with wye directly
  //////////////////////////////////////////////////////////////////////

  /**
   * Transform the left input of the given `Wye` using a `Process1`.
   */
  def attachL[I0,I,I2,O](p1: Process1[I0,I])(y: Wye[I,I2,O]): Wye[I0,I2,O] =  {
    y.step match {
      case Cont(emt@Emit(os),next) =>
        emt ++ attachL(p1)(Try(next(End)))

      case Cont(AwaitL(rcv),next) => p1.step match {
        case Cont(Emit(is),next1) =>
          attachL(Try(next1(End)))(feedL(is)(y))

        case Cont(AwaitP1.withFbT(rcv1), next1) =>
          Await(L[I0]: Env[I0,I2]#Y[I0],(r : Throwable \/ I0) =>
            Trampoline.suspend {
              rcv1(r).map(p=>attachL(p onHalt next1)(y))
            })

        case d@Done(rsn) => attachL(d.asHalt)(killL(rsn)(y))
      }

      case Cont(AwaitR.withFbT(rcv),next) =>
        Await(R[I2]: Env[I0,I2]#Y[I2],(r : Throwable \/ I2) =>
          Trampoline.suspend(rcv(r)).map(yy=>attachL(p1)(yy onHalt next))
        )

      case Cont(AwaitBoth(rcv),next) => p1.step match {
        case Cont(Emit(is),next1) =>
          attachL(Try(next1(End)))(feedL(is)(y))

        case Cont(AwaitP1.withFbT(rcv1), next1) =>
          Await(Both[I0,I2]: Env[I0,I2]#Y[ReceiveY[I0,I2]], (r: Throwable \/ ReceiveY[I0,I2]) =>
           Trampoline.suspend (r.fold(
             rsn => rcv1(left(rsn)).map(p=> attachL(p onHalt next1)(y))
             , _ match {
               case ReceiveL(i0) => rcv1(right(i0)).map(p => attachL(p onHalt next1)(y))
               case ReceiveR(i2) => Trampoline.done(attachL(p1)(feed1R(i2)(y)))
               case HaltL(rsn) => rcv1(left(rsn)).map(p=> attachL(p onHalt next1)(y))
               case HaltR(rsn) => Trampoline.done(attachL(p1)(killR(rsn)(y)))
             }
           )))

        case d@Done(rsn) => attachL(d.asHalt)(killL(rsn)(y))
      }

      case d@Done(rsn) => d.asHalt
    }

  }

  /**
   * Transform the right input of the given `Wye` using a `Process1`.
   */
  def attachR[I,I1,I2,O](p: Process1[I1,I2])(w: Wye[I,I2,O]): Wye[I,I1,O] =
    flip(attachL(p)(flip(w)))


//    w match {
//    case h@Halt(_) => h
//    case Emit(h,t) => Emit(h, attachL(p)(t))
//    case AwaitL(recv, fb, c) =>
//      p match {
//        case Emit(h, t) => attachL(t)(wye.feedL(h)(w))
//        case Await1(recvp, fbp, cp) =>
//          await(L[I0]: Env[I0,I2]#Y[I0])(
//            recvp andThen (attachL(_)(w)),
//            attachL(fbp)(w),
//            attachL(cp)(w))
//        case h@Halt(_) => attachL(h)(fb)
//      }
//
//    case AwaitR(recv, fb, c) =>
//      awaitR[I2].flatMap(recv andThen (attachL(p)(_))).
//      orElse(attachL(p)(fb), attachL(p)(c))
//    case AwaitBoth(recv, fb, c) =>
//      p match {
//        case Emit(h, t) => attachL(t)(scalaz.stream.wye.feedL(h)(w))
//        case Await1(recvp, fbp, cp) =>
//          await(Both[I0,I2]: Env[I0,I2]#Y[ReceiveY[I0,I2]])(
//          { case ReceiveL(i0) => attachL(p.feed1(i0))(w)
//          case ReceiveR(i2) => attachL(p)(feed1R(i2)(w))
//          case HaltL(End) => attachL(p.fallback)(w)
//          case HaltL(e) => attachL(p.causedBy(e))(haltL(e)(w))
//          case HaltR(e) => attachL(p)(haltR(e)(w))
//          },
//          attachL(fbp)(w),
//          attachL(cp)(w))
//        case h@Halt(End) => attachL(h)(fb)
//        case h@Halt(e) => attachL(h)(c.causedBy(e))
//      }
//  }



  /**
   * Feed a single `ReceiveY` value to a `Wye`.
   */
  def feed1[I,I2,O](r: ReceiveY[I,I2])(w: Wye[I,I2,O]): Wye[I,I2,O] =
    r match {
      case ReceiveL(i) => feed1L(i)(w)
      case ReceiveR(i2) => feed1R(i2)(w)
      case HaltL(e) => killL(e)(w)
      case HaltR(e) => killR(e)(w)
    }

  /** Feed a single value to the left branch of a `Wye`. */
  def feed1L[I,I2,O](i: I)(w: Wye[I,I2,O]): Wye[I,I2,O] =
    feedL(Vector(i))(w)

  /** Feed a single value to the right branch of a `Wye`. */
  def feed1R[I,I2,O](i2: I2)(w: Wye[I,I2,O]): Wye[I,I2,O] =
    feedR(Vector(i2))(w)

  /** Feed a sequence of inputs to the left side of a `Tee`. */
  def feedL[I,I2,O](i: Seq[I])(y: Wye[I,I2,O]): Wye[I,I2,O] = {
    @tailrec
    def go(in: Seq[I], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] = {
      cur.step match {
        case Cont(Emit(os), next) =>
          go(in, out :+ os, Try(next(End)))

        case Cont(AwaitL(rcv), next) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(in.head)) onHalt next)
          else emitAll(out.flatten) ++ cur

        case Cont(AwaitR.withFb(rcv), next) =>
          emitAll(out.flatten) ++
            (awaitOr(R[I2]: Env[I,I2]#Y[I2])
             (rsn => feedL(in)(rcv(left(rsn)) onHalt next))
             (i2 => feedL[I,I2,O](in)(Try(rcv(right(i2))) onHalt next)))


        case Cont(AwaitBoth(rcv), next) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(ReceiveY.ReceiveL(in.head))) onHalt next)
          else emitAll(out.flatten) ++ cur

        case Done(rsn)                  => emitAll(out.flatten).causedBy(rsn)

      }
    }
    go(i, Vector(), y)
  }



//  {
//    @annotation.tailrec
//    def go(in: Seq[I], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] =
//      if (in.nonEmpty) cur match {
//        case h@Halt(_) => emitSeq(out.flatten, h)
//        case Emit(h, t) => go(in, out :+ h, t)
//        case AwaitL(recv, fb, c) =>
//          val next =
//            try recv(in.head)
//            catch {
//              case End => fb
//              case e: Throwable => c.causedBy(e)
//            }
//          go(in.tail, out, next)
//        case AwaitBoth(recv, fb, c) =>
//          val next =
//            try recv(ReceiveY.ReceiveL(in.head))
//            catch {
//              case End => fb
//              case e: Throwable => c.causedBy(e)
//            }
//          go(in.tail, out, next)
//        case AwaitR(recv, fb, c) =>
//          emitSeq(out.flatten,
//            await(R[I2]: Env[I,I2]#Y[I2])(recv andThen (feedL(in)), feedL(in)(fb), feedL(in)(c)))
//      }
//      else emitSeq(out.flatten, cur)
//    go(i, Vector(), p)
//  }

  /** Feed a sequence of inputs to the right side of a `Tee`. */
  def feedR[I,I2,O](i2: Seq[I2])(y: Wye[I,I2,O]): Wye[I,I2,O] = {
    @tailrec
    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] = {
      cur.step match {
        case Cont(Emit(os), next) =>
          go(in, out :+ os, Try(next(End)))

        case Cont(AwaitR(rcv), next) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(in.head)) onHalt next)
          else emitAll(out.flatten) ++ cur

        case Cont(AwaitL.withFb(rcv), next) =>
          emitAll(out.flatten) ++
            (awaitOr(L[I]: Env[I,I2]#Y[I])
             (rsn => feedR(in)(rcv(left(rsn)) onHalt next))
             (i => feedR[I,I2,O](in)(Try(rcv(right(i))) onHalt next)))

        case Cont(AwaitBoth(rcv), next) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(ReceiveY.ReceiveR(in.head))) onHalt next)
          else emitAll(out.flatten) ++ cur

        case Done(rsn)                  => emitAll(out.flatten).causedBy(rsn)

      }
    }
    go(i2, Vector(), y)
  }

//  {
//
//    @annotation.tailrec
//    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] =
//      if (in.nonEmpty) cur match {
//        case h@Halt(_) => emitSeq(out.flatten, h)
//        case Emit(h, t) => go(in, out :+ h, t)
//        case AwaitR(recv, fb, c) =>
//          val next =
//            try recv(in.head)
//            catch {
//              case End => fb
//              case e: Throwable => c.causedBy(e)
//            }
//          go(in.tail, out, next)
//        case AwaitBoth(recv, fb, c) =>
//          val next =
//            try recv(ReceiveY.ReceiveR(in.head))
//            catch {
//              case End => fb
//              case e: Throwable => c.causedBy(e)
//            }
//          go(in.tail, out, next)
//        case AwaitL(recv, fb, c) =>
//          emitSeq(out.flatten,
//            await(L[I]: Env[I,I2]#Y[I])(recv andThen (feedR(in)), feedR(in)(fb), feedR(in)(c)))
//      }
//      else emitSeq(out.flatten, cur)
//    go(i, Vector(), p)
//  }

  /**
   * Convert right requests to left requests and vice versa.
   */
  def flip[I,I2,O](w: Wye[I,I2,O]): Wye[I2,I,O] =
     w.step match {
       case Cont(Emit(os),next) =>
         emitAll(os) ++ flip(Try(next(End)))

       case Cont(AwaitL.withFb(rcv),next) =>
         Await(R[I]: Env[I2,I]#Y[I], (r : Throwable \/ I) =>
           Trampoline.delay(flip(Try(rcv(r))))
         ) onHalt (r => flip(Try(next(r))))

       case Cont(AwaitR.withFb(rcv),next) =>
         Await(L[I2]: Env[I2,I]#Y[I2], (r : Throwable \/ I2) =>
           Trampoline.delay(flip(Try(rcv(r))))
         ) onHalt (r => flip(Try(next(r))))

       case Cont(AwaitBoth.withFb(rcv),next) =>
         Await(Both[I2,I], (r : Throwable \/ ReceiveY[I2,I]) =>
           Trampoline.delay(flip(Try(rcv(r.map(_.flip)))))
         ) onHalt (r => flip(Try(next(r))))

       case d@Done(rsn) => d.asHalt
     }




//    w match {
//    case h@Halt(_) => h
//    case Emit(h, t) => Emit(h, flip(t))
//    case AwaitL(recv, fb, c) =>
//      await(R[I]: Env[I2,I]#Y[I])(recv andThen (flip), flip(fb), flip(c))
//    case AwaitR(recv, fb, c) =>
//      await(L[I2]: Env[I2,I]#Y[I2])(recv andThen (flip), flip(fb), flip(c))
//    case AwaitBoth(recv, fb, c) =>
//      await(Both[I2,I])((t: ReceiveY[I2,I]) => flip(recv(t.flip)), flip(fb), flip(c))
//  }


  /**
   * Signals to wye, that Left side terminated.
   */
  def killL[I, I2, O](rsn0: Throwable)(y0: Wye[I, I2, O]): Wye[I, I2, O] = {
    def go(rsn: Throwable, y: Wye[I, I2, O]): Wye[I, I2, O] = {
      val ys = y.step
      debug(s"KillL $ys")
      ys match {
        case Cont(emt@Emit(os), next) =>
          emt ++ go(rsn, Try(next(End)))

        case Cont(AwaitL.withFb(rcv), next) =>
          suspend(go(rsn, Try(rcv(left(rsn))))
                  .onHalt(r => go(rsn, Try(next(r)))))

        case Cont(awt@AwaitR.is(), next) =>
          awt.extend(go(rsn, _))
          .onHalt(r => go(rsn, Try(next(r))))

        case Cont(AwaitBoth(rcv), next) =>
          suspend(go(rsn, Try(rcv(ReceiveY.HaltL(rsn))))
                  .onHalt(r => go(rsn, Try(next(r)))))

        case d@Done(rsn) => d.asHalt
      }
    }

    go(Kill(rsn0), y0)
  }


  //  {
  //    p match {
  //      case h@Halt(_) => h
//      case Emit(h, t) =>
//        val (nh,nt) = t.unemit
//        Emit(h ++ nh, haltL(e)(nt))
//      case AwaitL(rcv,fb,c) => p.killBy(e)
//      case AwaitR(rcv,fb,c) => await(R[I2]: Env[I,I2]#Y[I2])(rcv, haltL(e)(fb), haltL(e)(c))
//      case AwaitBoth(rcv,fb,c) =>
//        try rcv(ReceiveY.HaltL(e))
//        catch {
//          case End => fb
//          case e: Throwable =>  c.causedBy(e)
//        }
//    }
//  }
  /**
   * Signals to wye, that Right side terminated.
   */
  def killR[I, I2, O](rsn0: Throwable)(y0: Wye[I, I2, O]): Wye[I, I2, O] = {
    def go(rsn: Throwable, y: Wye[I, I2, O]): Wye[I, I2, O] = {
      val ys = y.step
      debug(s"KillR $ys")
      ys match {
        case Cont(emt@Emit(os), next) =>
          emt ++ go(rsn, Try(next(End)))

        case Cont(AwaitR.withFb(rcv), next) =>
          suspend(go(rsn, Try(rcv(left(rsn))))
                  .onHalt(r => go(rsn, Try(next(r)))))

        case Cont(awt@AwaitL.is(), next) =>
          awt.extend(go(rsn, _))
          .onHalt(r => go(rsn, Try(next(r))))

        case Cont(AwaitBoth(rcv), next) =>
          suspend(go(rsn, Try(rcv(ReceiveY.HaltR(rsn))))
                  .onHalt(r => go(rsn, Try(next(r)))))

        case d@Done(rsn) => d.asHalt
      }
    }
    go(Kill(rsn0), y0)
  }


  //{
  //    p match {
//      case h@Halt(_) => h
//      case Emit(h, t) =>
//        val (nh,nt) = t.unemit
//        Emit(h ++ nh, haltR(e)(nt))
//      case AwaitR(rcv,fb,c) => p.killBy(e)
//      case AwaitL(rcv,fb,c) => await(L[I]: Env[I,I2]#Y[I])(rcv, haltR(e)(fb), haltR(e)(c))
//      case AwaitBoth(rcv,fb,c) =>
//        try rcv(ReceiveY.HaltR(e))
//        catch {
//          case End => fb
//          case e: Throwable =>  c.causedBy(e)
//        }
//    }
//  }



  ////////////////////////////////////////////////////////////////////////
  // Request Algebra
  ////////////////////////////////////////////////////////////////////////

  /** Indicates required request side **/
  trait Request

  object Request {
    /** Left side **/
    case object L extends Request
    /** Right side **/
    case object R extends Request
    /** Both, or Any side **/
    case object Both extends Request
  }


  //////////////////////////////////////////////////////////////////////
  // De-constructors and type helpers
  //////////////////////////////////////////////////////////////////////

  type WyeAwaitL[I,I2,O] = Await[Env[I,I2]#Y,Env[I,Any]#Is[I],O]
  type WyeAwaitR[I,I2,O] = Await[Env[I,I2]#Y,Env[Any,I2]#T[I2],O]
  type WyeAwaitBoth[I,I2,O] = Await[Env[I,I2]#Y,Env[I,I2]#Y[ReceiveY[I,I2]],O]

  object AwaitL {
    def unapply[I,I2,O](self: Wye[I,I2,O]):
    Option[(I => Wye[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 0 => Some((i : I) => Try(rcv(right(i)).run))
      case _ => None
    }
    /** Like `AwaitL.unapply` only allows for extracting the fallback case as well **/
    object withFb {
      def unapply[I,I2,O](self: Wye[I,I2,O]):
      Option[(Throwable \/ I => Wye[I,I2,O])] = self match {
        case Await(req,rcv) if req.tag == 0 => Some((r : Throwable \/ I) => Try(rcv(r).run))
        case _ => None
      }
    }

    /** Like `AwaitL.unapply` only allows for extracting the fallback case as well on Trampoline **/
    object withFbT {
      def unapply[I,I2,O](self: Wye[I,I2,O]):
      Option[(Throwable \/ I => Trampoline[Wye[I,I2,O]])] = self match {
        case Await(req,rcv) if req.tag == 0 => Some(rcv)
        case _ => None
      }
    }

    /** Like `AwaitL.unapply` only allows fast test that wye is awaiting on left side **/
    object is {
      def unapply[I,I2,O](self: WyeAwaitL[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 0 => true
        case _ => false
      }
    }
  }


  object AwaitR {
    def unapply[I,I2,O](self: Wye[I,I2,O]):
    Option[(I2 => Wye[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 1 => Some((i2 : I2) => Try(rcv(right(i2)).run))
      case _ => None
    }
    /** Like `AwaitR.unapply` only allows for extracting the fallback case as well **/
    object withFb {
      def unapply[I,I2,O](self: Wye[I,I2,O]):
      Option[(Throwable \/ I2 => Wye[I,I2,O])] = self match {
        case Await(req,rcv) if req.tag == 1 => Some((r : Throwable \/ I2) => Try(rcv(r).run))
        case _ => None
      }
    }

    /** Like `AwaitR.unapply` only allows for extracting the fallback case as well on Trampoline **/
    object withFbT {
      def unapply[I,I2,O](self: Wye[I,I2,O]):
      Option[(Throwable \/ I2 => Trampoline[Wye[I,I2,O]])] = self match {
        case Await(req,rcv) if req.tag == 1 => Some(rcv)
        case _ => None
      }
    }

    /** Like `AwaitR.unapply` only allows fast test that wye is awaiting on right side **/
    object is {
      def unapply[I,I2,O](self: WyeAwaitR[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 1 => true
        case _ => false
      }
    }
  }
  object AwaitBoth {
    def unapply[I,I2,O](self: Wye[I,I2,O]):
    Option[(ReceiveY[I,I2] => Wye[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 2 => Some((r : ReceiveY[I,I2]) => Try(rcv(right(r)).run))
      case _ => None
    }

    /** Like `AwaitBoth.unapply` only allows for extracting the fallback case as well **/
    object withFb {
      def unapply[I,I2,O](self: Wye[I,I2,O]):
      Option[(Throwable \/ ReceiveY[I,I2] => Wye[I,I2,O])] = self match {
        case Await(req,rcv) if req.tag == 2 => Some((r : Throwable \/ ReceiveY[I,I2]) => Try(rcv(r).run))
        case _ => None
      }
    }

    /** Like `AwaitBoth.unapply` only allows for extracting the fallback case as well on Trampoline **/
    object withFbT {
      def unapply[I,I2,O](self: Wye[I,I2,O]):
      Option[(Throwable \/ ReceiveY[I,I2] => Trampoline[Wye[I,I2,O]])] = self match {
        case Await(req,rcv) if req.tag == 2 => Some(rcv)
        case _ => None
      }
    }

    /** Like `AwaitBoth.unapply` only allows fast test that wye is awaiting on both sides **/
    object is {
      def unapply[I,I2,O](self: WyeAwaitBoth[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 2 => true
        case _ => false
      }
    }

  }

  //////////////////////////////////////////////////////////////////
  // Implementation
  //////////////////////////////////////////////////////////////////

  /**
   * Implementation of wye.
   *
   * @param pl left process
   * @param pr right process
   * @param y0  wye to control queueing and merging
   * @param S  strategy, preferably executor service
   * @tparam L Type of left process element
   * @tparam R Type of right process elements
   * @tparam O Output type of resulting process
   * @return Process with merged elements.
   */
  def apply[L, R, O](pl: Process[Task, L], pr: Process[Task, R])(y0: Wye[L, R, O])(implicit S: Strategy): Process[Task, O] =
    suspend {

      sealed trait M
      case class ReadyL(h: Seq[L], next: Throwable => Process[Task, L]) extends M
      case class ReadyR(h: Seq[R], next: Throwable => Process[Task, R]) extends M
      case class DoneL(rsn: Throwable) extends M
      case class DoneR(rsn: Throwable) extends M
      case class Get(cb: (Throwable \/ Seq[O]) => Unit) extends M
      case class Terminate(cause: Throwable, cb: (Throwable \/ Unit) => Unit) extends M


      sealed trait SideState[A] {
        def isDone: Boolean = this match {
          case SideDone(_) => true
          case _           => false
        }
      }
      case class SideReady[A](cont: Throwable => Process[Task, A]) extends SideState[A]
      case class SideRunning[A](interrupt: (Throwable) => Unit) extends SideState[A]
      case class SideDone[A](cause: Throwable) extends SideState[A]


      //current state of the wye
      var yy: Wye[L, R, O] = y0

      //cb to be completed for `out` side
      var out: Option[(Throwable \/ Seq[O]) => Unit] = None

      //forward referenced actor
      var a: Actor[M] = null

      //Bias for reading from either left or right.
      var leftBias: Boolean = true

      // states of both sides
      var left: SideState[L] = SideReady(_ => pl)
      var right: SideState[R] = SideReady(_ => pr)


      // completes a callback fro downstream
      def completeOut(cb: (Throwable \/ Seq[O]) => Unit, r: Throwable \/ Seq[O]): Unit = {
        out = None
        S(cb(r))
      }


      // runs the left side, if left side is ready to be run or no-op
      // updates state of left side as well
      def runL: Unit = {
        left match {
          case SideReady(next) =>
            left = SideRunning(
              Try(next(End)).runAsync(_.fold(
              rsn => a ! DoneL(rsn)
              , { case (ls, n) => a ! ReadyL(ls, n) }
              ))
            )

          case _ => ()
        }

      }

      // right version of `runL`
      def runR: Unit = {
        right match {
          case SideReady(next) =>
            right = SideRunning(
              Try(next(End)).runAsync(_.fold(
              rsn => a ! DoneR(rsn)
              , { case (rs, next) => a ! ReadyR(rs, next) }
              ))
            )

          case _ => ()
        }
      }

      // terminates or interrupts the Left side
      def terminateL(rsn: Throwable): Unit = {
        left match {
          case SideReady(next)        =>
            left = SideRunning(_ => ()) //no-op as cleanup can`t be interrupted
            Try(next(End)).killBy(Kill(rsn)).runAsync(_.fold(
              rsn => a ! DoneL(rsn)
              , _ => a ! DoneL(new Exception("Invalid state after kill"))
            ))
          case SideRunning(interrupt) => interrupt(rsn)
          case _                      => ()
        }
      }

      // right version of `terminateL`
      def terminateR(rsn: Throwable): Unit = {
        right match {
          case SideReady(next)        =>
            right = SideRunning(_ => ()) //no-op as cleanup can`t be interrupted
            Try(next(End)).killBy(Kill(rsn)).runAsync(_.fold(
              rsn => a ! DoneR(rsn)
              , _ => a ! DoneR(new Exception("Invalid state after kill"))
            ))
          case SideRunning(interrupt) => interrupt(rsn)
          case _                      => ()
        }
      }

      /**
       * Tries to complete callback.
       * This does all side-effects based on the state of wye passed in
       *
       */
      @tailrec
      def tryCompleteOut(cb: (Throwable \/ Seq[O]) => Unit, y: Wye[L, R, O]): Wye[L, R, O] = {
        val ys = y.step
        debug(s"tryComplete ys: $ys, y: $y, L:$left, R:$right, out:$out")
        ys match {
          case Cont(Emit(Seq()), next)    => tryCompleteOut(cb, Try(next(End)))
          case Cont(Emit(os), next)       => completeOut(cb, \/-(os)); Try(next(End))
          case Cont(AwaitL.is(), _)       => runL; y
          case Cont(AwaitR.is(), next)    => runR; y
          case Cont(AwaitBoth.is(), next) => left match {
            case SideDone(rsn) if right.isDone => tryCompleteOut(cb, y.killBy(Kill(rsn)))
            case _ if leftBias                 => runL; runR; y
            case _                             => runR; runL; y
          }
          case d@Done(rsn)                =>

            terminateL(rsn)
            terminateR(rsn)
            debug(s"YY DONE   $rsn  L:$left R:$right")
            if (left.isDone && right.isDone) completeOut(cb, -\/(Kill(rsn)))

            d.asHalt
        }
      }

      // When downstream is registered tries to complete callback
      // otherwise just updates `yy`
      def complete(y: Wye[L, R, O]): Unit = {
        yy = out match {
          case Some(cb) => tryCompleteOut(cb, y)
          case None     => y
        }
      }


      a = Actor.actor[M]({
        case ReadyL(ls, next) => complete {
          debug(s"ReadyL $ls $next")
          leftBias = false
          left = SideReady(next)
          wye.feedL[L, R, O](ls)(yy)
        }

        case ReadyR(rs, next) => complete {
          debug(s"ReadyR $rs $next")
          leftBias = true
          right = SideReady(next)
          wye.feedR[L, R, O](rs)(yy)
        }

        case DoneL(rsn) => complete {
          debug(s"DoneL $rsn")
          leftBias = false
          left = SideDone(rsn)
          wye.killL(rsn)(yy)
        }

        case DoneR(rsn) => complete {
          debug(s"DoneR $rsn")
          leftBias = true
          right = SideDone(rsn)
          wye.killR(rsn)(yy)
        }

        case Get(cb) => complete {
          debug("Get")
          out = Some(cb)
          yy
        }

        case Terminate(rsn, cb) => complete {
          debug("Terminate")
          val cbOut = cb compose ((_: Throwable \/ Seq[O]) => \/-(()))
          out = Some(cbOut)
          yy.killBy(Kill(rsn))
        }
      })(S)

      (repeatEval(Task.async[Seq[O]](cb => a ! Get(cb))).flatMap(emitAll)) onHalt { rsn =>
        eval_(Task.async[Unit](cb => a ! Terminate(rsn, cb))).causedBy(rsn).swallowKill
      }

    }


}


protected[stream2] trait WyeOps[+O] {
  val self: Process[Task, O]

  /**
   * Like `tee`, but we allow the `Wye` to read non-deterministically
   * from both sides at once.
   *
   * If `y` is in the state of awaiting `Both`, this implementation
   * will continue feeding `y` from either left or right side,
   * until either it halts or _both_ sides halt.
   *
   * If `y` is in the state of awaiting `L`, and the left
   * input has halted, we halt. Likewise for the right side.
   *
   * For as long as `y` permits it, this implementation will _always_
   * feed it any leading `Emit` elements from either side before issuing
   * new `F` requests. More sophisticated chunking and fairness
   * policies do not belong here, but should be built into the `Wye`
   * and/or its inputs.
   *
   * The strategy passed in must be stack-safe, otherwise this implementation
   * will throw SOE. Preferably use one of the `Strategys.Executor(es)` based strategies
   */
  final def wye[O2, O3](p2: Process[Task, O2])(y: Wye[O, O2, O3])(implicit S: Strategy): Process[Task, O3] =
    scalaz.stream2.wye[O, O2, O3](self, p2)(y)(S)

  /** Non-deterministic version of `zipWith`. Note this terminates whenever one of streams terminate */
  def yipWith[O2,O3](p2: Process[Task,O2])(f: (O,O2) => O3)(implicit S:Strategy): Process[Task,O3] =
    self.wye(p2)(scalaz.stream2.wye.yipWith(f))

  /** Non-deterministic version of `zip`. Note this terminates whenever one of streams terminate */
  def yip[O2](p2: Process[Task,O2])(implicit S:Strategy): Process[Task,(O,O2)] =
    self.wye(p2)(scalaz.stream2.wye.yip)

  /** Non-deterministic interleave of both streams.
    * Emits values whenever either is defined. Note this terminates after BOTH sides terminate */
  def merge[O2>:O](p2: Process[Task,O2])(implicit S:Strategy): Process[Task,O2] =
    self.wye(p2)(scalaz.stream2.wye.merge)

  /** Non-deterministic interleave of both streams. Emits values whenever either is defined.
    * Note this terminates after BOTH sides terminate  */
  def either[O2>:O,O3](p2: Process[Task,O3])(implicit S:Strategy): Process[Task,O2 \/ O3] =
    self.wye(p2)(scalaz.stream2.wye.either)

}
