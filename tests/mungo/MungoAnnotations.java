import org.checkerframework.checker.mungo.lib.MungoTypestate;
import org.checkerframework.checker.mungo.lib.MungoRequires;
import org.checkerframework.checker.mungo.lib.MungoEnsures;

public class MungoAnnotations {

  public static void main1() {
    JavaIterator it = new JavaIterator();
    // :: warning: (it: JavaIterator{HasNext} | Moved)
    // :: error: (Cannot call hasNext on moved value)
    while (it.hasNext()) {
      // :: warning: (it: JavaIterator{Next})
      use1(it);
    }
  }

  // :: error: (Object did not complete its protocol. Type: JavaIterator{HasNext})
  public static void use1(@MungoRequires("Next") JavaIterator it) {
    // :: warning: (it: JavaIterator{Next})
    it.next();
  }

  public static void main2() {
    JavaIterator it = new JavaIterator();
    // :: warning: (it: JavaIterator{HasNext})
    while (it.hasNext()) {
      // :: warning: (it: JavaIterator{Next})
      use2(it);
    }
  }

  public static void use2(@MungoRequires("Next") @MungoEnsures("HasNext") JavaIterator it) {
    // :: warning: (it: JavaIterator{Next})
    it.next();
  }

  public static void main3() {
    JavaIterator it = new JavaIterator();
    // :: warning: (it: JavaIterator{HasNext})
    while (it.hasNext()) {
      // :: warning: (it: JavaIterator{Next})
      use2(it);
    }
  }

  // :: error: (Final type does not match what was specified by @MungoEnsures. Type: JavaIterator{Next})
  public static void use3(@MungoRequires("Next") @MungoEnsures("HasNext") JavaIterator it) {

  }

  public static void main4() {
    JavaIterator it = new JavaIterator();
    // :: warning: (it: JavaIterator{HasNext})
    while (it.hasNext()) {
      // :: warning: (it: JavaIterator{Next})
      use4(it);
    }
  }

  public static void use4(@MungoRequires("Next") @MungoEnsures("HasNext") JavaIterator it) {
    // :: warning: (it: JavaIterator{Next})
    if (it.hasNext()) {
      // :: warning: (it: JavaIterator{Next})
      it.next();
    } else {
      // :: warning: (it: Ended)
      // :: error: (Cannot override because object is not in the state specified by @MungoEnsures. Type: Ended)
      it = new JavaIterator();
    }
  }

  public static void main5() {
    JavaIterator it = new JavaIterator();
    // :: warning: (it: JavaIterator{HasNext})
    while (it.hasNext()) {
      // :: warning: (it: JavaIterator{Next})
      use5(it);
    }
  }

  // :: error: (Final type does not match what was specified by @MungoEnsures. Type: JavaIterator{HasNext} | Ended)
  public static void use5(@MungoRequires("Next") @MungoEnsures("HasNext") JavaIterator it) {
    // :: warning: (it: JavaIterator{Next})
    if (it.hasNext()) {
      // :: warning: (it: JavaIterator{Next})
      it.next();
    } else {
      int i = 3;
      while (i-- > 0) {
        // :: warning: (it: JavaIterator{HasNext} | Ended)
        // :: error: (Cannot override because object has not ended its protocol. Type: JavaIterator{HasNext} | Ended)
        // :: error: (Cannot override because object is not in the state specified by @MungoEnsures. Type: JavaIterator{HasNext} | Ended)
        it = new JavaIterator();
      }
    }
  }

}
