# Tai-e, A1 Note

在写Tai-e 作业A1时候的笔记

## Key Interface & Class

- `pascal.taie.analysis.dataflow.analysis.DataflowAnalysis`
    - 抽象的数据流分析类，是具体的数据流分析与求解器之间的接口。
- `pascal.taie.ir.exp.Exp`
    - 用于表示程序中的所有表达式，有值的雨具都是与表达式

![Subclasses of Exp](https://tai-e.pascal-lab.net/pa1/exp-subclasses.png)

在 Tai-e 的 IR 中，把表达式分为两类：LValue 和 RValue。前者表示赋值语句左侧的表达式，如变量（`x = … `）、字段访问（`x.f = …`）或数组访问（`x[i] = …`）；后者对应地表示赋值语句右侧的表达式，如数值字面量（`… = 1;`）或二元表达式（`… = a + b;`）。（常量和二元表达式是不能放在=左边的）

> 采用的IR是3AC，=右边允许有二元表达式

而有些表达式既可用于左值，也可用于右值，就比如变量（用Var类表示)。因为本次作业只进行活跃变量分析，所以你实际上只需要关注 `Var` 类就足够了。

- `pascal.taie.ir.stmt.Stmt`

对于一个典型的程序设计语言来说，**每个表达式都属于某条特定的语句。**为了实现活跃变量分析，你需要获得某条语句中**定义或使用的所有表达式中的变量**。`Stmt` 类贴心地为这两种操作提供了对应的接口：

```java
Optional<LValue> getDef()
List<RValue> getUses()
```

每条 `Stmt` 至多只可能定义一个变量、而可能使用零或多个变量，因此我们使用 `Optional` 和 `List` 包装了 `getDef()` 和 `getUses()` 的结果。

- `pascal.taie.analysis.dataflow.fact.SetFact<Var>`

这个**泛型类**用于把 data fact 组织成一个集合。它提供了各种集合操作，如添加、删除元素，取交集、并集等。你同样需要阅读源码和注释来理解如何使用这个类表示活跃变量分析中的各种 data fact。

- `pascal.taie.analysis.dataflow.analysis.LiveVariableAnalysis`

---

exit节点是没有sucessor的，所以其outFact为null

所以在处理算法的时候是不考虑的

这个算法是不需要在result中保留每个节点的Out的，每次都需要重新计算

| BFS  | DFS  |
| ---- | ---- |
| 0.03 |      |
| 0.00 |      |
| 0.00 |      |
| 0.00 |      |
| 0.00 |      |
| 0.01 |      |

迭代次数

| BFS  | DFS  | iteration |
| ---- | ---- | --------- |
| 29   | 29   | 11        |
| 30   | 30   | 13        |
| 23   | 24   | 14        |
| 9    | 9    | 4         |
| 16   | 19   | 9         |
| 73   | 71   | 24        |

