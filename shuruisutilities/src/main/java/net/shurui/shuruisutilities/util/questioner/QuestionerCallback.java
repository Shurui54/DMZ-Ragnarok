package net.shurui.shuruisutilities.util.questioner;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

public interface QuestionerCallback
{

    public void respond(Boolean response) throws CommandSyntaxException;

}