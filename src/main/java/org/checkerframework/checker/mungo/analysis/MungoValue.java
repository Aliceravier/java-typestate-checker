package org.checkerframework.checker.mungo.analysis;

import org.checkerframework.framework.flow.CFAbstractValue;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeMirror;
import java.util.Set;

public class MungoValue extends CFAbstractValue<MungoValue> {
  public MungoValue(MungoAnalysis analysis, Set<AnnotationMirror> annotations, TypeMirror underlyingType) {
    super(analysis, annotations, underlyingType);
  }

  @Override
  public String toString() {
    return "MungoValue{"
      + "annotations="
      + annotations
      + ", underlyingType="
      + underlyingType
      + '}';
  }
}