package com.example.coupenapp.telegram;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the Telegram bot configuration in a dedicated SharedPreferences file
 * (kept separate from the app's CoupenAppPrefs so nothing collides).
 */
public class TelegramConfig {

    private static final String PREFS = "TelegramBotPrefs";
    private final SharedPreferences p;

    public TelegramConfig(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getBotToken()            { return p.getString("bot_token", ""); }
    public void   setBotToken(String v)    { p.edit().putString("bot_token", v).apply(); }

    /** The Telegram group/channel title to watch (default "Ritik"). */
    public String getGroupName()           { return p.getString("group_name", "Ritik"); }
    public void   setGroupName(String v)   { p.edit().putString("group_name", v).apply(); }

    public boolean isAutoStart()           { return p.getBoolean("auto_start", false); }
    public void    setAutoStart(boolean v) { p.edit().putBoolean("auto_start", v).apply(); }

    /** Last processed Telegram update_id (prevents reprocessing old messages). */
    public long getOffset()                { return p.getLong("offset", 0); }
    public void setOffset(long v)          { p.edit().putLong("offset", v).apply(); }

    public int  getTotalReceived()         { return p.getInt("total_received", 0); }
    public void setTotalReceived(int v)    { p.edit().putInt("total_received", v).apply(); }

    public int  getTotalProcessed()        { return p.getInt("total_processed", 0); }
    public void setTotalProcessed(int v)   { p.edit().putInt("total_processed", v).apply(); }
}
