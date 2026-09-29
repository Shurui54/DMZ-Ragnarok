package net.shurui.shuruisutilities.data.v2;

public @interface SerializationGroup
{

    public String name() default DataManager.DEFAULT_GROUP;

}
