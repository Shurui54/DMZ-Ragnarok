package net.shurui.shuruisutilities.core.moduleLauncher;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
// If you need more than one thing in the SU API, make a module class using this
// annotation.
public @interface SUModule
{

    // identifying mark shown in logs (esp. errors), no spaces. an outside module overriding another must
    // share its name.
    String name();

    int version();

    // core modules load first
    boolean isCore() default false;

    // override another module. you can only override core modules.
    boolean doesOverride() default false;

    boolean canDisable() default true;

    boolean defaultModule() default true;

    // built-in modules: the ShuruisUtilities class. others: your @Mod class.
    Class<?> parentMod();

    // populated with an instance of this module
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD })
    public @interface Instance
    {
    }

    // populated with an instance of this module's parent mod
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD })
    public @interface ParentMod
    {
    }

    // populated with this module's ModuleContainer
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD })
    public @interface Container
    {
    }

    // populated with a File for this module's dir
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD })
    public @interface ModuleDir
    {
    }

    // precondition check: no args, returns boolean
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Preconditions
    {

    }

}
