package scala.collection.workstealing



import scala.language.experimental.macros
import scala.reflect.macros._



trait ParIterableOperations[T] {

  def foreach[U](f: T => U): Unit = macro ParIterableOperations.foreach[T, U]

  def fold[U >: T](z: U)(op: (U, U) => U): U = macro ParIterableOperations.fold[T, U]

  def reduce[U >: T](op: (U, U) => U): U = macro ParIterableOperations.reduce[T, U]

  def aggregate[S](z: =>S)(combop: (S, S) => S)(seqop: (S, T) => S): S = macro ParIterableOperations.aggregate[T, S]

  def sum[U >: T](implicit num: Numeric[U]): U = macro ParIterableOperations.sum[T, U]

  def product[U >: T](implicit num: Numeric[U]): U = macro ParIterableOperations.product[T, U]

  def count(p: T => Boolean): Int = macro ParIterableOperations.count[T]

  def min[U >: T](implicit ord: Ordering[U]): T = macro ParIterableOperations.min[T, U]

  def max[U >: T](implicit ord: Ordering[U]): T = macro ParIterableOperations.max[T, U]

  def find(p: T => Boolean): Option[T] = macro ParIterableOperations.find[T]

  def forall(p: T => Boolean): Boolean = macro ParIterableOperations.forall[T]

  def exists(p: T => Boolean): Boolean = macro ParIterableOperations.exists[T]

  def copyToArray[U >: T](arr: Array[U], start: Int, len: Int): Unit = macro ParIterableOperations.copyToArray[T, U]

}


object ParIterableOperations {
  
  def foreach[T: c.WeakTypeTag, U: c.WeakTypeTag](c: Context)(f: c.Expr[T => U]): c.Expr[Unit] = {
    import c.universe._

    val (lv, func) = c.functionExpr2Local[T => U](f)
    val callee = c.Expr[Nothing](c.applyPrefix)
    val kernel = reify {
      lv.splice
      val xs = callee.splice.asInstanceOf[Workstealing[T]]
      xs.invokeParallelOperation(new xs.Kernel[T, Unit] {
        def zero = ()
        def combine(a: Unit, b: Unit) = a
        def apply(node: xs.N[Unit], chunkSize: Int) = {
          var left = chunkSize
          while (left > 0) {
            func.splice(node.next())
            left -= 1
          }
        }
      })
    }
    c.inlineAndReset(kernel)
  }

  def fold[T: c.WeakTypeTag, U >: T: c.WeakTypeTag](c: Context)(z: c.Expr[U])(op: c.Expr[(U, U) => U]): c.Expr[U] = {
    import c.universe._

    val (lv, oper) = c.functionExpr2Local[(U, U) => U](op)
    val callee = c.Expr[Nothing](c.applyPrefix)
    val kernel = reify {
      lv.splice
      val xs = callee.splice.asInstanceOf[Workstealing[T]]
      xs.invokeParallelOperation(new xs.Kernel[T, U] {
        val zero = z.splice
        def combine(a: U, b: U) = oper.splice(a, b)
        def apply(node: xs.N[U], chunkSize: Int) = {
          var left = chunkSize
          var sum = zero
          while (left > 0) {
            sum = oper.splice(sum, node.next())
            left -= 1
          }
          sum
        }
      })
    }
    c.inlineAndReset(kernel)
  }

  val nil = new AnyRef {}

  def reduce[T: c.WeakTypeTag, U >: T: c.WeakTypeTag](c: Context)(op: c.Expr[(U, U) => U]): c.Expr[U] = {
    import c.universe._

    val (lv, oper) = c.functionExpr2Local[(U, U) => U](op)
    val callee = c.Expr[Nothing](c.applyPrefix)
    val kernel = reify {
      lv.splice
      val xs = callee.splice.asInstanceOf[Workstealing[T]]
      val rs = xs.invokeParallelOperation(new xs.Kernel[T, Any] {
        val zero = ParIterableOperations.nil
        def combine(a: Any, b: Any) = {
          if (a == zero) b
          else if (b == zero) a
          else oper.splice(a.asInstanceOf[U], b.asInstanceOf[U])
        }
        def apply(node: xs.N[Any], chunkSize: Int) = {
          if (chunkSize == 0) zero
          else {
            var left = chunkSize - 1
            var sum: U = node.next()
            while (left > 0) {
              sum = oper.splice(sum, node.next())
              left -= 1
            }
            sum
          }
        }
      })
      if (rs == ParIterableOperations.nil) throw new java.lang.UnsupportedOperationException
      else rs.asInstanceOf[U]
    }
    c.inlineAndReset(kernel)
  }

  def aggregate[T: c.WeakTypeTag, S: c.WeakTypeTag](c: Context)(z: c.Expr[S])(combop: c.Expr[(S, S) => S])(seqop: c.Expr[(S, T) => S]): c.Expr[S] = {
    import c.universe._

    val (seqlv, seqoper) = c.functionExpr2Local[(S, T) => S](seqop)
    val (comblv, comboper) = c.functionExpr2Local[(S, S) => S](combop)
    val callee = c.Expr[Nothing](c.applyPrefix)
    val kernel = reify {
      seqlv.splice
      comblv.splice
      val xs = callee.splice.asInstanceOf[Workstealing[T]]
      xs.invokeParallelOperation(new xs.Kernel[T, S] {
        def zero = z.splice
        def combine(a: S, b: S) = comboper.splice(a, b)
        def apply(node: xs.N[S], chunkSize: Int) = {
          var left = chunkSize
          var sum = zero
          while (left > 0) {
            sum = seqoper.splice(sum, node.next())
            left -= 1
          }
          sum
        }
      })
    }
    c.inlineAndReset(kernel)
  }

  // TODO fix these methods to store arguments in a local when necessary
  def sum[T: c.WeakTypeTag, U >: T: c.WeakTypeTag](c: Context)(num: c.Expr[Numeric[U]]): c.Expr[U] = {
    import c.universe._

    val zero = reify {
      num.splice.zero
    }
    val op = reify {
      (x: U, y: U) => num.splice.plus(x, y)
    }
    fold[T, U](c)(zero)(op)
  }

  def product[T: c.WeakTypeTag, U >: T: c.WeakTypeTag](c: Context)(num: c.Expr[Numeric[U]]): c.Expr[U] = {
    import c.universe._

    val zero = reify {
      num.splice.one
    }
    val op = reify {
      (x: U, y: U) => num.splice.times(x, y)
    }
    fold[T, U](c)(zero)(op)
  }

  def count[T: c.WeakTypeTag](c: Context)(p: c.Expr[T => Boolean]): c.Expr[Int] = {
    import c.universe._

    val zero = reify { 0 }
    val combop = reify {
      (x: Int, y: Int) => x + y
    }
    val seqop = reify {
      (x: Int, y: T) =>
      if (p.splice(y)) x + 1 else x
    }
    aggregate[T, Int](c)(zero)(combop)(seqop)
  }

  def min[T: c.WeakTypeTag, U >: T: c.WeakTypeTag](c: Context)(ord: c.Expr[Ordering[U]]): c.Expr[T] = {
    import c.universe._

    val op = reify {
      (x: T, y: T) => if (ord.splice.compare(x, y) <= 0) x else y
    }
    reduce[T, T](c)(op)
  }

  def max[T: c.WeakTypeTag, U >: T: c.WeakTypeTag](c: Context)(ord: c.Expr[Ordering[U]]): c.Expr[T] = {
    import c.universe._

    val op = reify {
      (x: T, y: T) => if (ord.splice.compare(x, y) >= 0) x else y
    }
    reduce[T, T](c)(op)
  }

  def find[T: c.WeakTypeTag](c: Context)(p: c.Expr[T => Boolean]): c.Expr[Option[T]] = {
    import c.universe._

    val (lv, pred) = c.functionExpr2Local[T => Boolean](p)
    val callee = c.Expr[Nothing](c.applyPrefix)
    val kernel = reify {
      lv.splice
      val xs = callee.splice.asInstanceOf[Workstealing[T]]
      xs.invokeParallelOperation(new xs.Kernel[T, Option[T]] {
        def zero = None
        def combine(a: Option[T], b: Option[T]) = if (a.nonEmpty) a else b
        def apply(node: xs.N[Option[T]], chunkSize: Int) = {
          var left = chunkSize
          var found: Option[T] = None
          while (left > 0) {
            val elem = node.next()
            if (pred.splice(elem)) {
              found = Some(elem)
              notTermFlag = false
              left = 0
            }
            left -= 1
          }
          found
        }
      })
    }
    c.inlineAndReset(kernel)
  }

  def forall[T: c.WeakTypeTag](c: Context)(p: c.Expr[T => Boolean]): c.Expr[Boolean] = {
    import c.universe._

    val np = reify {
      (x: T) => !p.splice(x)
    }
    val found = find[T](c)(np)
    reify {
      found.splice.isEmpty
    }
  }

  def exists[T: c.WeakTypeTag](c: Context)(p: c.Expr[T => Boolean]): c.Expr[Boolean] = {
    import c.universe._

    val found = find[T](c)(p)
    reify {
      found.splice.nonEmpty
    }
  }

  def copyToArray[T: c.WeakTypeTag, U >: T: c.WeakTypeTag](c: Context)(arr: c.Expr[Array[U]], start: c.Expr[Int], len: c.Expr[Int]): c.Expr[Unit] = {
    import c.universe._

    val callee = c.Expr[Nothing](c.applyPrefix)
    val kernel = reify {
      val xs = callee.splice.asInstanceOf[Workstealing[T]]
      xs.invokeParallelOperation(new xs.Kernel[T, Int] {
        private def mathmin(a: Int, b: Int) = if (a < b) a else b
        override def afterExpand(tree: xs.Node[T, Int]) {
          // TODO inspect the remaining work on the left and on the right and set lresult
        }
        def zero = -1
        def combine(a: Int, b: Int) = -1
        def apply(node: xs.N[Int], chunkSize: Int) = {
          var i = node.lresult
          var left = mathmin(i + chunkSize, mathmin(arr.splice.length, start.splice + len.splice))
          while (left > 0) {
            arr.splice(i) = node.next()
            left -= 1
            i += 1
          }
          node.lresult = i
          i
        }
      })
      ()
    }
    c.inlineAndReset(kernel)
  }

}
















