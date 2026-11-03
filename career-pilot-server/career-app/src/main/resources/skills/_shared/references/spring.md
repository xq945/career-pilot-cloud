# Spring 框架参考要点

本文件是出题参考（考察方向锚点），不是题目本身——题目应结合岗位与候选人背景改写为具体问题。

## IoC 与 AOP
- Bean 生命周期（实例化 → 属性注入 → Aware 回调 → 初始化前后 → 使用 → 销毁）与常用扩展点（BeanPostProcessor / ApplicationListener）
- 循环依赖的三级缓存解法与无法解决的场景（构造器注入、prototype 作用域）
- AOP 的实现方式（JDK 动态代理与 CGLIB）与失效场景（同类内自调用、private 方法、final 类）

## 事务（重点考察区）
- @Transactional 的传播行为（REQUIRED / REQUIRES_NEW / NESTED 的差异与适用场景）
- @Transactional 的典型失效场景：自调用绕过代理、非 public 方法、异常被捕获未抛出、rollbackFor 缺省只回滚运行时异常
- 编程式事务与声明式事务的取舍；**事务内做远程调用 / 模型调用的危害**
- 事务边界与锁的配合：大事务的锁持有时间、批量操作的提交策略

## Spring Boot
- 自动配置原理（@EnableAutoConfiguration → AutoConfiguration.imports 的条件装配）
- 自动配置的排除方式与自定义 starter 的要点
- 配置绑定：@ConfigurationProperties 的松散绑定、构造器绑定与 fail-fast 校验
- 过滤器 / 拦截器 / AOP 的执行顺序与各自适用场景
