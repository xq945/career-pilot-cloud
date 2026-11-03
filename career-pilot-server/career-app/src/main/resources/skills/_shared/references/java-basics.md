# Java 基础参考要点

本文件是出题参考（考察方向锚点），不是题目本身——题目应结合岗位与候选人背景改写为具体问题。

## 集合与基础
- ArrayList 与 LinkedList 的结构差异及适用场景；HashMap 的哈希扰动、扩容阈值（0.75）、链表转红黑树条件（链表长度 > 8 且容量 ≥ 64）
- HashMap 的线程安全问题与 ConcurrentHashMap 的演进（分段锁 → CAS + synchronized 锁桶头）
- String 的不可变性与常量池；equals / hashCode 契约及只重写其一的后果

## 并发
- synchronized 锁升级（偏向锁 → 轻量级 → 重量级）与对象头 Mark Word
- AQS 的核心（state + CLH 等待队列）与 ReentrantLock 公平 / 非公平的差异
- 线程池七参数、任务提交与拒绝策略、无界队列的隐患
- volatile 的可见性与禁止重排序；happens-before 规则的实际应用

## JVM
- 运行时内存区域划分与对象分配过程；逃逸分析与栈上分配
- GC 算法（标记清除 / 复制 / 标记整理）与常见收集器（G1 的 Region 化、ZGC 的并发标记整理）
- 类加载过程与双亲委派；打破双亲委派的实际场景（SPI、热部署）
- 常见问题定位：OOM 类型判别、jstack / jmap / arthas 的使用场景
