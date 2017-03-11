/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.tail

import monix.eval.{Coeval, Task}
import monix.execution.exceptions.DummyException
import monix.tail.Iterant.{NextGen, NextSeq, Suspend}
import scala.util.{Failure, Success}

object IterantFlatMapSuite extends BaseTestSuite {
  test("AsyncStream.flatMap equivalence with List.flatMap") { implicit s =>
    check2 { (stream: AsyncStream[Int], f: Int => List[Long]) =>
      val result = stream.flatMap(x => AsyncStream.fromList(f(x))).toListL
      val expected = stream.toListL.map((list: List[Int]) => list.flatMap(f))
      result === expected
    }
  }

  test("AsyncStream.flatMap can handle errors") { implicit s =>
    val dummy = DummyException("dummy")
    val stream = AsyncStream.raiseError[Int](dummy)
    assertEquals(stream, stream.flatMap(x => AsyncStream(x)))
  }

  test("AsyncStream.next.flatMap guards against direct user code errors") { implicit s =>
    val dummy = DummyException("dummy")
    var isCanceled = false

    val stream = AsyncStream.nextS(1, Task(AsyncStream.empty), Task { isCanceled = true })
    val result = stream.flatMap[Int](_ => throw dummy).toListL.runAsync

    s.tick()
    assertEquals(result.value, Some(Failure(dummy)))
    assert(isCanceled, "isCanceled should be true")
  }

  test("AsyncStream.nextSeq.flatMap guards against direct user code errors") { implicit s =>
    val dummy = DummyException("dummy")
    var isCanceled = false

    val stream = AsyncStream.nextSeqS(List(1,2,3).iterator, Task(AsyncStream.empty), Task { isCanceled = true })
    val result = stream.flatMap[Int](_ => throw dummy).toListL.runAsync

    s.tick()
    assertEquals(result.value, Some(Failure(dummy)))
    assert(isCanceled, "isCanceled should be true")
  }

  test("AsyncStream.next.flatMap chains stop") { implicit s =>
    var effects = Vector.empty[Int]
    val stop1T = Task.eval { effects = effects :+ 1 }
    val stream1: AsyncStream[Int] =
      AsyncStream.nextS(1, Task.now(AsyncStream.haltS[Int](None)), stop1T)

    val stop2T = Task.eval { effects = effects :+ 2 }
    val stream2: AsyncStream[Int] =
      AsyncStream.nextS(2, Task.now(AsyncStream.haltS[Int](None)), stop2T)

    val stop3T = Task.eval { effects = effects :+ 3 }
    val stream3: AsyncStream[Int] =
      AsyncStream.nextS(3, Task.now(AsyncStream.haltS[Int](None)), stop3T)

    val composed =
      for (x <- stream1; y <- stream2; z <- stream3)
        yield x + y + z

    composed match {
      case Iterant.Next(head, _, stop) =>
        assertEquals(head, 6)
        assertEquals(stop.runSyncMaybe, Right(()))
        assertEquals(effects, Vector(3,2,1))
      case state =>
        fail(s"Invalid state: $state")
    }
  }

  test("AsyncStream.nextSeq.flatMap chains stop") { implicit s =>
    def firstNext[A](streamable: Iterant[Task,A]): Task[Iterant[Task,A]] =
      streamable match {
        case Suspend(rest, _) =>
          rest.flatMap(firstNext)
        case NextGen(gen, rest, stop) =>
          Task.now(NextSeq(gen.iterator, rest, stop))
        case _ =>
          Task.now(streamable)
      }

    var effects = Vector.empty[Int]
    val stop1T = Task.eval { effects = effects :+ 1 }
    val stream1: AsyncStream[Int] =
      AsyncStream.fromList(List(1)).doOnEarlyStop(stop1T)

    val stop2T = Task.eval { effects = effects :+ 2 }
    val stream2: AsyncStream[Int] =
      AsyncStream.fromList(List(2)).doOnEarlyStop(stop2T)

    val stop3T = Task.eval { effects = effects :+ 3 }
    val stream3: AsyncStream[Int] =
      AsyncStream.fromList(List(3)).doOnEarlyStop(stop3T)

    val composed =
      for (x <- stream1; y <- stream2; z <- stream3)
        yield x + y + z

    firstNext(composed).runSyncMaybe match {
      case Right(Iterant.NextSeq(head, _, stop)) =>
        assertEquals(head.toList, List(6))
        assertEquals(stop.runSyncMaybe, Right(()))
        assertEquals(effects, Vector(3,2,1))
      case state =>
        fail(s"Invalid state: $state")
    }
  }

  test("AsyncStream.nextSeq.flatMap works for large lists") { implicit s =>
    val count = 100000
    val list = (0 until count).toList
    val sumTask = AsyncStream.fromList(list)
      .flatMap(x => AsyncStream.fromList(List(x,x,x)))
      .foldLeftL(0L)(_+_)

    val f = sumTask.runAsync; s.tick()
    assertEquals(f.value, Some(Success(3 * (count.toLong * (count - 1) / 2))))
  }

  test("AsyncStream.flatMap should protect against indirect user errors") { implicit s =>
    check2 { (l: List[Int], idx: Int) =>
      val dummy = DummyException("dummy")
      val list = if (l.isEmpty) List(1) else l
      val source = arbitraryListToAsyncStream(list, idx)
      val received = source.flatMap(_ => AsyncStream.raiseError[Int](dummy))
      received === AsyncStream.haltS[Int](Some(dummy))
    }
  }

  test("AsyncStream.flatMap should protect against direct exceptions") { implicit s =>
    check2 { (l: List[Int], idx: Int) =>
      val dummy = DummyException("dummy")
      val list = if (l.isEmpty) List(1) else l
      val source = arbitraryListToAsyncStream(list, idx)
      val received = source.flatMap[Int](_ => throw dummy)
      received === AsyncStream.haltS[Int](Some(dummy))
    }
  }

  test("AsyncStream.flatMap should protect against broken cursors") { implicit s =>
    check1 { (prefix: AsyncStream[Int]) =>
      val dummy = DummyException("dummy")
      val cursor = new ThrowExceptionIterator(dummy)
      val error = AsyncStream.nextSeqS(cursor, Task.now(AsyncStream.empty), Task.unit)
      val stream = (prefix ++ error).flatMap(x => AsyncStream.now(x))
      stream === AsyncStream.haltS[Int](Some(dummy))
    }
  }

  test("AsyncStream.flatMap should protect against broken generators") { implicit s =>
    check1 { (prefix: AsyncStream[Int]) =>
      val dummy = DummyException("dummy")
      val generator = new ThrowExceptionIterable(dummy)
      val error = AsyncStream.nextGenS(generator, Task.now(AsyncStream.empty), Task.unit)
      val stream = (prefix ++ error).flatMap(x => AsyncStream.now(x))
      stream === AsyncStream.haltS[Int](Some(dummy))
    }
  }

  test("LazyStream.flatMap equivalence with List.flatMap") { implicit s =>
    check2 { (stream: LazyStream[Int], f: Int => List[Long]) =>
      val result = stream.flatMap(x => LazyStream.fromList(f(x))).toListL
      val expected = stream.toListL.map((list: List[Int]) => list.flatMap(f))
      result === expected
    }
  }

  test("LazyStream.flatMap can handle errors") { implicit s =>
    val dummy = DummyException("dummy")
    val stream = LazyStream.raiseError[Int](dummy)
    assertEquals(stream, stream.flatMap(x => LazyStream.pure(x)))
  }

  test("LazyStream.next.flatMap guards against direct user code errors") { _ =>
    val dummy = DummyException("dummy")
    var isCanceled = false

    val stream = LazyStream.nextS(1, Coeval(LazyStream.empty), Coeval { isCanceled = true })
    val result = stream.flatMap[Int](_ => throw dummy).toListL.runTry

    assertEquals(result, Failure(dummy))
    assert(isCanceled, "isCanceled should be true")
  }

  test("LazyStream.nextSeq.flatMap guards against direct user code errors") { _ =>
    val dummy = DummyException("dummy")
    var isCanceled = false

    val stream = LazyStream.nextSeqS(List(1,2,3).iterator, Coeval(LazyStream.empty), Coeval { isCanceled = true })
    val result = stream.flatMap[Int](_ => throw dummy).toListL.runTry

    assertEquals(result, Failure(dummy))
    assert(isCanceled, "isCanceled should be true")
  }

  test("LazyStream.next.flatMap chains stop") { implicit s =>
    var effects = Vector.empty[Int]
    val stop1T = Coeval.eval { effects = effects :+ 1 }
    val stream1: LazyStream[Int] =
      LazyStream.nextS(1, Coeval.now(LazyStream.haltS[Int](None)), stop1T)

    val stop2T = Coeval.eval { effects = effects :+ 2 }
    val stream2: LazyStream[Int] =
      LazyStream.nextS(2, Coeval.now(LazyStream.haltS[Int](None)), stop2T)

    val stop3T = Coeval.eval { effects = effects :+ 3 }
    val stream3: LazyStream[Int] =
      LazyStream.nextS(3, Coeval.now(LazyStream.haltS[Int](None)), stop3T)

    val composed =
      for (x <- stream1; y <- stream2; z <- stream3)
        yield x + y + z

    composed match {
      case Iterant.Next(head, _, stop) =>
        assertEquals(head, 6)
        assertEquals(stop.value, ())
        assertEquals(effects, Vector(3,2,1))
      case state =>
        fail(s"Invalid state: $state")
    }
  }

  test("LazyStream.nextSeq.flatMap chains stop") { implicit s =>
    def firstNext[A](streamable: Iterant[Coeval,A]): Coeval[Iterant[Coeval,A]] =
      streamable match {
        case Suspend(rest, _) =>
          rest.flatMap(firstNext)
        case NextGen(gen, rest, stop) =>
          Coeval.now(NextSeq(gen.iterator, rest, stop))
        case _ =>
          Coeval.now(streamable)
      }

    var effects = Vector.empty[Int]
    val stop1T = Coeval.eval { effects = effects :+ 1 }
    val stream1: LazyStream[Int] =
      LazyStream.fromList(List(1)).doOnEarlyStop(stop1T)

    val stop2T = Coeval.eval { effects = effects :+ 2 }
    val stream2: LazyStream[Int] =
      LazyStream.fromList(List(2)).doOnEarlyStop(stop2T)

    val stop3T = Coeval.eval { effects = effects :+ 3 }
    val stream3: LazyStream[Int] =
      LazyStream.fromList(List(3)).doOnEarlyStop(stop3T)

    val composed =
      for (x <- stream1; y <- stream2; z <- stream3)
        yield x + y + z

    firstNext(composed).value match {
      case Iterant.NextSeq(head, _, stop) =>
        assertEquals(head.toList, List(6))
        assertEquals(stop.value, ())
        assertEquals(effects, Vector(3,2,1))
      case state =>
        fail(s"Invalid state: $state")
    }
  }

  test("LazyStream.flatMap should protect against indirect user errors") { implicit s =>
    check2 { (l: List[Int], idx: Int) =>
      val dummy = DummyException("dummy")
      val list = if (l.isEmpty) List(1) else l
      val source = arbitraryListToLazyStream(list, idx)
      val received = source.flatMap(_ => LazyStream.raiseError[Int](dummy))
      received === LazyStream.haltS[Int](Some(dummy))
    }
  }

  test("LazyStream.flatMap should protect against direct exceptions") { implicit s =>
    check2 { (l: List[Int], idx: Int) =>
      val dummy = DummyException("dummy")
      val list = if (l.isEmpty) List(1) else l
      val source = arbitraryListToLazyStream(list, idx)
      val received = source.flatMap[Int](_ => throw dummy)
      received === LazyStream.haltS[Int](Some(dummy))
    }
  }

  test("LazyStream.flatMap should protect against broken cursors") { implicit s =>
    check1 { (prefix: LazyStream[Int]) =>
      val dummy = DummyException("dummy")
      val cursor = new ThrowExceptionIterator(dummy)
      val error = LazyStream.nextSeqS(cursor, Coeval.now(LazyStream.empty), Coeval.unit)
      val stream = (prefix ++ error).flatMap(x => LazyStream.now(x))
      stream === LazyStream.haltS[Int](Some(dummy))
    }
  }

  test("LazyStream.flatMap should protect against broken generators") { implicit s =>
    check1 { (prefix: LazyStream[Int]) =>
      val dummy = DummyException("dummy")
      val cursor = new ThrowExceptionIterable(dummy)
      val error = LazyStream.nextGenS(cursor, Coeval.now(LazyStream.empty), Coeval.unit)
      val stream = (prefix ++ error).flatMap(x => LazyStream.now(x))
      stream === LazyStream.haltS[Int](Some(dummy))
    }
  }
}
