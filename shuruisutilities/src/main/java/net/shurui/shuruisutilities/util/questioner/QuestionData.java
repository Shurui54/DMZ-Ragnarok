package net.shurui.shuruisutilities.util.questioner;

import net.shurui.shuruisutilities.core.misc.Translator;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;

public class QuestionData
{

    private Player target;

    private Player source;

    private String question;

    private int timeout;

    private long startTime;

    private QuestionerCallback callback;

    public QuestionData(Player target, String question, QuestionerCallback callback, int timeout,
            Player source)
    {
        this.target = target;
        this.timeout = timeout;
        this.callback = callback;
        this.source = source;
        this.question = question;
        this.startTime = System.currentTimeMillis();
    }

    public void sendQuestion()
    {
        ChatOutputHandler.sendMessage(target.createCommandSourceStack(), question);
        sendYesNoMessage();
    }

    public void sendYesNoMessage()
    {
        MutableComponent yesMessage = Component.literal("/accept");
        ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/accept");
        yesMessage.withStyle((style) -> style.withClickEvent(click));
        yesMessage.withStyle(ChatFormatting.RED);
        yesMessage.withStyle(ChatFormatting.UNDERLINE);

        MutableComponent noMessage = Component.literal("/deny");
        ClickEvent click1 = new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/deny");
        noMessage.withStyle((style) -> style.withClickEvent(click1));
        noMessage.withStyle(ChatFormatting.RED);
        noMessage.withStyle(ChatFormatting.UNDERLINE);

        MutableComponent yesNoMessage = Component.literal("Type ");
        yesNoMessage.append(yesMessage);
        yesNoMessage.append(Component.literal(" or "));
        yesNoMessage.append(noMessage);
        yesNoMessage.append(Component.literal(" " + Translator.format("(timeout: %d)", timeout)));

        ChatOutputHandler.sendMessage(target.createCommandSourceStack(), yesNoMessage);
    }

    protected void doAnswer(Boolean answer) throws CommandSyntaxException
    {
        callback.respond(answer);
    }

    public void confirm() throws CommandSyntaxException
    {
        Questioner.confirm(target);
        // TODO: Maybe send a message, because it was not confirmed through user
        // interaction?
    }

    public void deny() throws CommandSyntaxException
    {
        Questioner.deny(target);
        // TODO: Maybe send a message, because it was not denied through user
        // interaction?
    }

    public void cancel() throws CommandSyntaxException
    {
        Questioner.cancel(target);
        // TODO: Maybe send a message, because it was not canceled through user
        // interaction?
    }

    public Player getTarget()
    {
        return target;
    }

    public Player getSource()
    {
        return source;
    }

    public String getQuestion()
    {
        return question;
    }

    public int getTimeout()
    {
        return timeout;
    }

    public long getStartTime()
    {
        return startTime;
    }

    public QuestionerCallback getCallback()
    {
        return callback;
    }

    public boolean isTimeout()
    {
        return (System.currentTimeMillis() - startTime) / 1000L > timeout;
    }

}
