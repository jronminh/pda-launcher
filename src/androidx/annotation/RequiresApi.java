package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-only stub (no Gradle/AndroidX in this build). */
@Retention(RetentionPolicy.CLASS)
@Target({
        ElementType.METHOD,
        ElementType.CONSTRUCTOR,
        ElementType.TYPE,
        ElementType.FIELD
})
public @interface RequiresApi {
    int value() default 1;
}
