# 思路

## Call Graph Builder

首先是建立Call Graph: ==**Essentially, a call graph is a set of call edges from call-sites to their target methods**==

所以最后CallGraph的泛型为Invoke和JMethod

```java
public CallGraph<Invoke, JMethod> build()
```

