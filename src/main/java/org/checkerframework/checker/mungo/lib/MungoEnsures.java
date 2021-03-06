package org.checkerframework.checker.mungo.lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Use: @MungoEnsures("Next")

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE_USE})
public @interface MungoEnsures {
  String[] value();
}
