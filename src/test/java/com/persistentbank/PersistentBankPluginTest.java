package com.persistentbank;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Development launcher for the Persistent Bank plugin.
 *
 * Not a JUnit test — despite the filename, this class is here because the
 * RuneLite client lives on the testImplementation classpath (see build.gradle),
 * and putting the launcher in src/test/java lets us depend on the client jar
 * at runtime without shipping it inside the plugin's production jar.
 *
 * Run this class from IntelliJ (Run > Run 'PersistentBankPluginTest.main()')
 * to fire up RuneLite with the plugin pre-registered. The actual Plugin Hub
 * build uses compileOnly scope and does NOT include the client.
 */
public class PersistentBankPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(PersistentBankPlugin.class);
        RuneLite.main(args);
    }
}
