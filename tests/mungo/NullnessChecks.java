import org.checkerframework.checker.mungo.lib.MungoNullable;

public class NullnessChecks {

  public String obj = new String("some text");

  public static void main1() {
    @MungoNullable NullnessChecks n = null;
    // :: warning: (n: Null)
    // :: error: (Cannot access obj on null)
    System.out.println(n.obj);
  }

  public static void main2() {
    @MungoNullable NullnessChecks n = null;
    // :: warning: (n: Null)
    // :: warning: (cast.unsafe)
    // :: error: (Object did not complete its protocol. Type: Object)
    Object obj = (Object) n;
  }

  public static void main3() {
    // :: error: (assignment.type.incompatible)
    NullnessChecks n = null;
  }

  public static void main4() {
    NullnessChecks n = new NullnessChecks();
    if (n == null) {
      // :: warning: (n: Bottom)
      System.out.println(n.obj);
    }
  }

  // https://github.com/typetools/checker-framework/issues/3267

  public static void foo(@MungoNullable String obj) {
    // :: warning: (obj: NoProtocol | Null)
    if ((obj != null) == false) {
      // :: warning: (obj: NoProtocol | Null)
      // :: error: (Cannot call toString on null)
      obj.toString();
    }
  }

  public static void bar(@MungoNullable String obj) {
    // :: warning: (obj: NoProtocol | Null)
    if (!(obj == null) == false) {
      // :: warning: (obj: NoProtocol | Null)
      // :: error: (Cannot call toString on null)
      obj.toString();
    }
  }

  public static void baz(@MungoNullable String obj) {
    // :: warning: (obj: NoProtocol | Null)
    if ((obj == null) == true) {
      // :: warning: (obj: NoProtocol | Null)
      // :: error: (Cannot call toString on null)
      obj.toString();
    }
  }

}
