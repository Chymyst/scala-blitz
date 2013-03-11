package scala.collection.workstealing
package test



import scala.reflect.ClassTag
import scala.collection._



object TreeWorkstealingTest extends App {

  case class Tree(val left: Tree, val right: Tree, val height: Int, val size: Int, val element: Int) {
    def isLeaf = left == null
  }

  val treeIsTree = new TreeWorkstealing.IsTree[Tree] {
    def left(tree: Tree): Tree = tree.left
    def right(tree: Tree): Tree = tree.right
    def size(tree: Tree): Int = tree.size
    def height(tree: Tree): Int = tree.height
    def isLeaf(tree: Tree): Boolean = tree.isLeaf
    def tag: ClassTag[Tree] = implicitly[ClassTag[Tree]]
    def external = false
  }

  val externalTreeIsTree = new TreeWorkstealing.IsTree[Tree] {
    def left(tree: Tree): Tree = tree.left
    def right(tree: Tree): Tree = tree.right
    def size(tree: Tree): Int = tree.size
    def height(tree: Tree): Int = tree.height
    def isLeaf(tree: Tree): Boolean = tree.isLeaf
    def tag: ClassTag[Tree] = implicitly[ClassTag[Tree]]
    def external = true
  }

  val workstealing = new TreeWorkstealing[Int, Tree] {
    val isTree = treeIsTree
    def config = ???
    def newRoot[R]: Ptr[Int, R] = ???
    def size: Int = ???
  }

  val extworkstealing = new TreeWorkstealing[Int, Tree] {
    val isTree = externalTreeIsTree
    def config = Workstealing.DefaultConfig
    def newRoot[R]: Ptr[Int, R] = ???
    def size: Int = ???
  }

  def toNode(root: Tree): workstealing.TreeNode[Int, Unit] = {
    val stack = TreeWorkstealing.initializeStack(root)(treeIsTree)
    class Wsnd(l: workstealing.Ptr[Int, Unit], r: workstealing.Ptr[Int, Unit])(rt: Tree, st: Array[AnyRef], fe: Int, te: Int, is: Int)
    extends workstealing.TreeNode[Int, Unit](l, r)(rt, st, fe, te, is) {
      def newTreeNode(l: workstealing.Ptr[Int, Unit], r: workstealing.Ptr[Int, Unit])(root: Tree, stack: Array[AnyRef], firstElem: Int, totalElems: Int, initStep: Int) = {
        new Wsnd(l, r)(root, stack, firstElem, totalElems, initStep)
      }
      def createIterator = new DefaultIterator[Int]() {
        def extractElement(t: Tree) = t.element
      }
    }
    new Wsnd(null, null)(root, stack, 0, root.size, 1)
  }

  def toExtNode(root: Tree): extworkstealing.TreeNode[Int, Unit] = {
    val stack = TreeWorkstealing.initializeStack(root)(externalTreeIsTree)
    class Wsnd(l: extworkstealing.Ptr[Int, Unit], r: extworkstealing.Ptr[Int, Unit])(rt: Tree, st: Array[AnyRef], fe: Int, te: Int, is: Int)
    extends extworkstealing.TreeNode[Int, Unit](l, r)(rt, st, fe, te, is) {
      def newTreeNode(l: extworkstealing.Ptr[Int, Unit], r: extworkstealing.Ptr[Int, Unit])(root: Tree, stack: Array[AnyRef], firstElem: Int, totalElems: Int, initStep: Int) = {
        new Wsnd(l, r)(root, stack, firstElem, totalElems, initStep)
      }
      def createIterator = new DefaultIterator[Int]() {
        def extractElement(t: Tree) = t.element
      }
    }
    new Wsnd(null, null)(root, stack, 0, root.size, 1)
  }

  def testChunk(wsnd: workstealing.TreeNode[Int, Unit], chunk: Int, elems: mutable.ArrayBuffer[Int]) {
    var left = chunk
    while (left > 0) {
      elems += wsnd.next()
      left -= 1
    }
  }

  def testAdvance(root: Tree, step: Int, expectedChunks: Seq[Int], expectedElems: Seq[Int]) {
    val elems = mutable.ArrayBuffer[Int]()
    val chunks = mutable.ArrayBuffer[Int]()
    val wsnd = toNode(root)
    var loop = true
    while (loop) {
      val chunk = wsnd.advance(step)
      chunks += chunk
      testChunk(wsnd, chunk, elems)
      if (chunk == -1) loop = false
    }
    assert(chunks == expectedChunks, "chunks: " + chunks.mkString(", ") + " vs. expected " + expectedChunks.mkString(", "))
    assert(elems == expectedElems, "elements: " + elems.mkString(", ") + " vs. expected " + expectedElems.mkString(", "))
  }

  def leaf(x: Int) = Tree(null, null, 0, 1, x)
  def dual(x: Int, y: Int, z: Int) = bind(leaf(x), y, leaf(z))
  def bind(l: Tree, x: Int, r: Tree) = Tree(l, r, math.max(l.height, r.height) + 1, l.size + r.size + 1, x)

  testAdvance(
    leaf(0),
    1,
    Seq(1, -1),
    Seq(0)
  )

  testAdvance(
    dual(0, 1, 2),
    1,
    Seq(1, 1, 1, -1),
    Seq(0, 1, 2)
  )

  testAdvance(
    dual(0, 1, 2),
    2,
    Seq(1, 1, 1, -1),
    Seq(0, 1, 2)
  )

  testAdvance(
    bind(leaf(0), 1, dual(2, 3, 4)),
    1,
    Seq(1, 1, 1, 1, 1, -1),
    Seq(0, 1, 2, 3, 4)
  )

  testAdvance(
    bind(leaf(0), 1, dual(2, 3, 4)),
    4,
    Seq(1, 1, 3, -1),
    Seq(0, 1, 2, 3, 4)
  )

  testAdvance(
    bind(dual(0, 1, 2), 3, bind(leaf(4), 5, dual(6, 7, 8))),
    8,
    Seq(3, 1, 5, -1),
    Seq(0, 1, 2, 3, 4, 5, 6, 7, 8)
  )

  testAdvance(
    bind(dual(0, 1, 2), 3, bind(leaf(4), 5, dual(6, 7, 8))),
    1,
    Seq(1, 1, 1, 1, 1, 1, 1, 1, 1, -1),
    Seq(0, 1, 2, 3, 4, 5, 6, 7, 8)
  )

  testAdvance(
    bind(dual(0, 1, 2), 3, bind(leaf(4), 5, dual(6, 7, 8))),
    4,
    Seq(3, 1, 1, 1, 3, -1),
    Seq(0, 1, 2, 3, 4, 5, 6, 7, 8)
  )

  def createTree(range: Range): Tree = {
    if (range.length == 1) leaf(range(0))
    else if (range.length == 3) dual(range(0), range(1), range(2))
    else {
      val cut = if ((range.length / 2) % 2 == 1) range.length / 2 else range.length / 2 + 1
      val firsthalf = range.take(cut)
      val secondhalf = range.drop(cut)
      bind(createTree(firsthalf), secondhalf.head, createTree(secondhalf.tail))
    }
  }

  def createExternalTree(range: Range): Tree = {
    if (range.length == 1) leaf(range(0))
    else {
      val l = createExternalTree(range.take(range.length / 2))
      val r = createExternalTree(range.drop(range.length / 2))
      Tree(l, r, math.max(l.height, r.height) + 1, l.size + r.size, -1)
    }
  }

  def testIteration(range: Range, step: Int, external: Boolean = false) {
    val seen = mutable.ArrayBuffer[Int]()
    val root = if (external) createExternalTree(range) else createTree(range)
    val wsnd = if (external) toExtNode(root) else toNode(root)
    var loop = true
    while (loop) {
      var chunk = wsnd.advance(step)
      if (chunk == -1) loop = false
      while (chunk > 0) {
        seen += wsnd.next()
        chunk -= 1
      }
    }
    assert(seen == range, seen.mkString(", ") + " vs. range " + range.mkString(", "))
  }

  testIteration(0 until 1, 1)
  testIteration(0 until 3, 1)
  testIteration(0 until 5, 1)
  testIteration(0 until 7, 1)
  testIteration(0 until 7, 2)
  testIteration(0 until 7, 4)
  testIteration(0 until 15, 1)
  testIteration(0 until 15, 2)
  testIteration(0 until 15, 4)
  testIteration(0 until 15, 8)
  testIteration(0 until 127, 1)
  testIteration(0 until 127, 2)
  testIteration(0 until 127, 4)
  testIteration(0 until 127, 16)
  testIteration(0 until 127, 32)
  testIteration(0 until 1001, 1)
  testIteration(0 until 1001, 2)
  testIteration(0 until 1001, 4)
  testIteration(0 until 1001, 16)
  testIteration(0 until 1001, 32)
  testIteration(0 until 1001, 64)
  testIteration(0 until 1001, 128)

  testIteration(0 until 1, 1, true)
  testIteration(0 until 2, 1, true)
  testIteration(0 until 3, 1, true)
  testIteration(0 until 4, 1, true)
  testIteration(0 until 5, 1, true)
  testIteration(0 until 6, 1, true)
  testIteration(0 until 7, 1, true)
  testIteration(0 until 8, 1, true)
  testIteration(0 until 8, 2, true)
  testIteration(0 until 8, 4, true)
  testIteration(0 until 32, 1, true)
  testIteration(0 until 32, 4, true)
  testIteration(0 until 32, 8, true)
  testIteration(0 until 64, 1, true)
  testIteration(0 until 64, 4, true)
  testIteration(0 until 64, 8, true)
  testIteration(0 until 64, 16, true)
  testIteration(0 until 64, 32, true)
  testIteration(0 until 1164, 1, true)
  testIteration(0 until 1164, 4, true)
  testIteration(0 until 1164, 8, true)
  testIteration(0 until 1164, 16, true)
  testIteration(0 until 1164, 32, true)
  testIteration(0 until 1164, 64, true)
  testIteration(0 until 1164, 128, true)
  testIteration(0 until 1164, 256, true)

  def testStealing(range: Range, step: Int) {
    val root = createExternalTree(range)
    val wsnd = toExtNode(root)
    val ptr = new extworkstealing.Ptr[Int, Unit](null, 0)(wsnd)

    val stealer = new Thread {
      override def run() {
        wsnd.markStolen()
      }
    }
    stealer.start()

    def workOn(wsnd: extworkstealing.TreeNode[Int, Unit]) = {
      val seen = mutable.ArrayBuffer[Int]()
      var loop = true
      while (loop) {
        var chunk = wsnd.advance(step)
        if (chunk == -1) loop = false
        while (chunk > 0) {
          seen += wsnd.next()
          chunk -= 1
        }
      }
      seen
    }
    val seen = workOn(wsnd)
    assert(seen == range.take(seen.length), seen + " vs. " + range.take(seen.length))
    assert(seen.length + wsnd.elementsRemaining == range.length, seen.length + " + " + wsnd.elementsRemaining + " vs. " + range.length + " in " + wsnd.nodeString)

    if (wsnd.state eq Workstealing.StolenOrExpanded) {
      val expanded = wsnd.newExpanded(ptr, null, new extworkstealing.Kernel[Int, Unit] {
        def zero = ()
        def combine(a: Unit, b: Unit) = a
        def apply(n: extworkstealing.N[Unit], chsz: Int) = ???
      })
      val left = expanded.left.child.repr
      val right = expanded.right.child.repr
  
      val leftseen = workOn(left)
      val rightseen = workOn(right)
  
      //println(seen)
      //println(wsnd.nodeString)
      //println(leftseen)
      //println(rightseen)
  
      assert(seen ++ leftseen ++ rightseen == range, "\n" + seen + "\n" + leftseen + "\n" + rightseen + "\nRange:\n" + range)
    }
  }

  testStealing(0 until 4, 1)
  testStealing(0 until 8, 1)
  testStealing(0 until 16, 1)
  testStealing(0 until 16, 4)
  testStealing(0 until 32, 1)
  testStealing(0 until 32, 4)
  testStealing(0 until 64, 1)
  testStealing(0 until 64, 4)
  testStealing(0 until 128, 1)
  testStealing(0 until 128, 4)
  testStealing(0 until 128, 8)
  testStealing(0 until 128, 16)
  testStealing(0 until 128, 32)
  testStealing(0 until 512, 1)
  testStealing(0 until 512, 4)
  testStealing(0 until 512, 8)
  testStealing(0 until 512, 16)
  testStealing(0 until 512, 32)
  testStealing(0 until 512, 64)
  testStealing(0 until 512, 128)
  testStealing(0 until 512, 256)
  testStealing(0 until 1024, 1)
  testStealing(0 until 1024, 4)
  testStealing(0 until 1024, 8)
  testStealing(0 until 1024, 16)
  testStealing(0 until 1024, 32)
  testStealing(0 until 1024, 64)
  testStealing(0 until 1024, 128)
  testStealing(0 until 1024, 256)
  testStealing(0 until 2000, 128)
  testStealing(0 until 2000, 500)
  testStealing(0 until 2000, 600)
  testStealing(0 until 2000, 700)
  testStealing(0 until 2000, 800)
  testStealing(0 until 2000, 900)
  testStealing(0 until 2000, 1000)
  testStealing(0 until 5000, 2)
  testStealing(0 until 5000, 32)
  testStealing(0 until 5000, 512)

}





















