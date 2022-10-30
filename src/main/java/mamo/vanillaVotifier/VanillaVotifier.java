/*
 * Copyright (C) 2016  Matteo Morena
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package mamo.vanillaVotifier;

import jline.console.ConsoleReader;
import jline.console.UserInterruptException;
import jline.console.completer.ArgumentCompleter;
import jline.console.completer.ArgumentCompleter.WhitespaceArgumentDelimiter;
import jline.console.completer.Completer;
import jline.console.completer.StringsCompleter;
import mamo.vanillaVotifier.event.Event;
import mamo.vanillaVotifier.event.ServerStoppedEvent;
import mamo.vanillaVotifier.exception.InvalidPrivateKeyFileException;
import mamo.vanillaVotifier.exception.InvalidPublicKeyFileException;
import mamo.vanillaVotifier.exception.PrivateKeyFileNotFoundException;
import mamo.vanillaVotifier.exception.PublicKeyFileNotFoundException;
import mamo.vanillaVotifier.utils.RsaUtils;
import mamo.vanillaVotifier.utils.TimestampUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.scanner.ScannerException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.Writer;
import java.net.BindException;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.TimeUnit;
import java.util.List;

public class VanillaVotifier {
	@NotNull protected LanguagePack languagePack;
	@NotNull protected Writer writer;
	@NotNull protected Logger logger;
	@NotNull protected YamlConfig config;
	@NotNull protected VotifierServer server;
	@NotNull protected Tester tester;

	public VanillaVotifier(@NotNull LanguagePack languagePack, @NotNull Writer logWriter, @NotNull File configFile) {
		this.languagePack = languagePack;
		writer = logWriter;
		logger = new Logger(this);
		if (configFile.getName().toLowerCase().endsWith(".json")) {
			config = new YamlConfig(new File(configFile.getParent(), configFile.getName().substring(0, configFile.getName().length() - ".json".length()) + ".yaml"), new JsonConfig(configFile));
		} else {
			config = new YamlConfig(configFile);
		}
		server = new VotifierServer(this);
		tester = new Tester(this);
	}

	public static void main(@Nullable String[] arguments) {
		String[] javaVersion = System.getProperty("java.version").split("\\.");
		if (!((javaVersion.length >= 2 && ((Integer.parseInt(javaVersion[0]) == 1 && Integer.parseInt(javaVersion[1]) >= 6))) || Integer.parseInt(javaVersion[0]) >= 2)) {
			System.out.println(("You need at least Java 1.6 to run this program! Current version: " + System.getProperty("java.version") + "."));
			return;
		}

		final ConsoleReader reader;
		try {
			reader = new ConsoleReader();
		} catch (Exception e) { // IOException
			System.out.println("Unexpected exception while initializing console reader: " + ExceptionUtils.getStackTrace(e));
			return;
		}
		WhitespaceArgumentDelimiter delimiter = new WhitespaceArgumentDelimiter();
		reader.setExpandEvents(false);
		reader.setHandleUserInterrupt(true);
		reader.addCompleter(new StringsCompleter("help", "info", "stop", "restart", "genkeypair", "showkey", "testquery", "testvote"));
		reader.addCompleter(new ArgumentCompleter(delimiter, new StringsCompleter("showkey"), new StringsCompleter("pub", "priv")));
		reader.addCompleter(new ArgumentCompleter(delimiter, new StringsCompleter("testquery"), new StringsCompleter("VOTE"), new AnyCompleter(), new AnyCompleter(), new AnyCompleter(), new Completer() {
			@Override
			public int complete(String buffer, int cursor, List<CharSequence> candidates) {
				return new StringsCompleter("'" + TimestampUtils.getTimestamp() + "'").complete(buffer, cursor, candidates);
			}
		}));

		final VanillaVotifier votifier = new VanillaVotifier(new LanguagePack("mamo/vanillaVotifier/lang", "lang"), reader.getOutput(), new File("config.json").exists() && !new File("config.yaml").exists() ? new File("config.json") : new File("config.yaml"));
		if (!(votifier.loadConfig() && votifier.startServer())) {
			return;
		}

		while (true) {
			String[] args;
			try {
				String command = reader.readLine();
				if (command == null) {
					TimeUnit.SECONDS.sleep(1);
					continue;
				}
				args = delimiter.delimit(command, command.length()).getArguments();
				if (args == null) {
					args = new String[]{};
				}
				for (int i = 0; i < args.length; i++) {
					args[i] = StringEscapeUtils.unescapeJava(args[i]);
				}
			} catch (UserInterruptException e) {
				votifier.stopServer();
				break;
			} catch (Exception e) {
				votifier.getLogger().printlnTranslation("s57", new SimpleEntry<String, Object>("exception", e));
				votifier.stopServer();
				break;
			}
			if (args.length == 0) {
				continue;
			}
			if (args[0].equals("stop")) {
				if (args.length == 1) {
					votifier.stopServer();
					break;
				} else {
					System.out.println(votifier.getLanguagePack().getString("s17"));
				}
			} else if (args[0].equals("restart")) {
				if (args.length == 1) {
					votifier.getServer().getListeners().add(new Listener() {
						@Override
						public void onEvent(@NotNull Event event) {
							if (event instanceof ServerStoppedEvent) {
								if (votifier.loadConfig() && votifier.startServer()) {
									votifier.getServer().getListeners().remove(this);
								} else {
									System.exit(0);
								}
							}
						}
					});
					votifier.stopServer();
				} else {
					System.out.println(votifier.getLanguagePack().getString("s56"));
				}
			} else if (args[0].equals("genkeypair")) {
				int keySize;
				if (args.length == 1) {
					keySize = 2048;
				} else if (args.length == 2) {
					try {
						keySize = Integer.parseInt(args[1]);
					} catch (NumberFormatException e) {
						System.out.println(votifier.getLanguagePack().getString("s19"));
						continue;
					}
					if (keySize < 512) {
						System.out.println(votifier.getLanguagePack().getString("s51"));
						continue;
					}
					if (keySize > 16384) {
						System.out.println(votifier.getLanguagePack().getString("s52"));
						continue;
					}
				} else {
					System.out.println(votifier.getLanguagePack().getString("s20"));
					continue;
				}
				votifier.getLogger().printlnTranslation("s16");
				votifier.getConfig().generateKeyPair(keySize);
				try {
					votifier.getConfig().saveKeyPair();
				} catch (Exception e) {
					votifier.getLogger().printlnTranslation("s21", new SimpleEntry<String, Object>("exception", e));
				}
				votifier.getLogger().printlnTranslation("s23", new SimpleEntry<String, Object>("key", RsaUtils.keyToString(votifier.getConfig().getKeyPair().getPublic())));
			} else if (args[0].equals("testvote")) {
				if (args.length == 2) {
					try {
						votifier.getTester().testVote(new Vote("TesterService", args[1], votifier.getConfig().getInetSocketAddress().getAddress().getHostName()));
					} catch (Exception e) { // GeneralSecurityException, IOException
						votifier.getLogger().printlnTranslation("s27", new SimpleEntry<String, Object>("exception", e));
					}
				} else {
					System.out.println(votifier.getLanguagePack().getString("s26"));
				}
			} else if (args[0].equals("testquery")) {
				if (args.length >= 2) {
					try {
						StringBuilder message = new StringBuilder();
						for (int i = 1; i < args.length - 1; i++) {
							message.append(args[i]).append("\n");
						}
						message.append(args[args.length - 1]);
						votifier.getTester().testQuery(message.toString());
					} catch (Exception e) { // GeneralSecurityException, IOException
						votifier.getLogger().printlnTranslation("s35", new SimpleEntry<String, Object>("exception", e));
					}
				} else {
					System.out.println(votifier.getLanguagePack().getString("s34"));
				}
			} else if (args[0].equals("help")) {
				if (args.length == 1) {
					System.out.println(votifier.getLanguagePack().getString("s31"));
				} else {
					System.out.println(votifier.getLanguagePack().getString("s32"));
				}
			} else if (args[0].equals("info")) {
				if (args.length == 1) {
					System.out.println(votifier.getLanguagePack().getString("s40"));
				} else {
					System.out.println(votifier.getLanguagePack().getString("s41"));
				}
			} else if (args[0].equals("showkey")) {
				if (args.length == 2) {
					if (args[1].equals("pub") || args[1].equals("public")) {
						System.out.println(RsaUtils.keyToString(votifier.getConfig().getKeyPair().getPublic()));
						continue;
					} else if (args[1].equals("priv") || args[1].equals("private")) {
						System.out.println(RsaUtils.keyToString(votifier.getConfig().getKeyPair().getPrivate()));
						continue;
					}
				}
				System.out.println(votifier.getLanguagePack().getString("s63"));
			} else {
				System.out.println(votifier.getLanguagePack().getString("s33"));
			}
		}
	}

	protected boolean loadConfig() {
		getLogger().printlnTranslation("s24");
		try {
			getConfig().load();
			getLogger().printlnTranslation("s25");
			return true;
		} catch (JSONException e) {
			getLogger().printlnTranslation("s45", new SimpleEntry<String, Object>("exception", e.getMessage().replaceAll("'", "\"")));
		} catch (ScannerException e) {
			getLogger().printlnTranslation("s66", new SimpleEntry<String, Object>("exception", e.getMessage().replaceAll("'", "\"")));
		} catch (ParserException e) {
			getLogger().printlnTranslation("s66", new SimpleEntry<String, Object>("exception", e.getMessage().replaceAll("'", "\"")));
		} catch (PublicKeyFileNotFoundException e) {
			getLogger().printlnTranslation("s49");
		} catch (PrivateKeyFileNotFoundException e) {
			getLogger().printlnTranslation("s50");
		} catch (InvalidPublicKeyFileException e) {
			getLogger().printlnTranslation("s47");
		} catch (InvalidPrivateKeyFileException e) {
			getLogger().printlnTranslation("s48");
		} catch (FileNotFoundException e) {
			if (getConfig().getConfigFile().exists()) {
				getLogger().printlnTranslation("s18");
			} else {
				getLogger().printlnTranslation("s15", new SimpleEntry<String, Object>("exception", e));
			}
		} catch (Exception e) {
			getLogger().printlnTranslation("s15", new SimpleEntry<String, Object>("exception", e));
		}
		return false;
	}

	protected boolean startServer() {
		try {
			getServer().start();
			return true;
		} catch (BindException e) {
			getLogger().printlnTranslation("s38", new SimpleEntry<String, Object>("port", getConfig().getInetSocketAddress().getPort()));
		} catch (Exception e) {
			getLogger().printlnTranslation("s13", new SimpleEntry<String, Object>("exception", e));
		}
		return false;
	}

	protected boolean stopServer() {
		try {
			getServer().stop();
			return true;
		} catch (Exception e) {
			getLogger().printlnTranslation("s12", new SimpleEntry<String, Object>("exception", e));
		}
		return false;
	}

	@NotNull
	public Writer getWriter() {
		return writer;
	}

	@NotNull
	public LanguagePack getLanguagePack() {
		return languagePack;
	}

	@NotNull
	public Logger getLogger() {
		return logger;
	}

	@NotNull
	public Config getConfig() {
		return config;
	}

	@NotNull
	public VotifierServer getServer() {
		return server;
	}

	@NotNull
	public Tester getTester() {
		return tester;
	}
}