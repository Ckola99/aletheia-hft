package com.aletheia.execution.fix;

import quickfix.*;
import java.io.FileInputStream;

public class FixLogonTest implements Application {

	private final String password;

	public FixLogonTest(String password) {
		this.password = password;
	}

	@Override
	public void onCreate(SessionID sessionId) {
		System.out.println("Session created: " + sessionId);
	}

	@Override
	public void onLogon(SessionID sessionId) {
		System.out.println("LOGON SUCCESS: " + sessionId);
	}

	@Override
	public void onLogout(SessionID sessionId) {
		System.out.println("Logout: " + sessionId);
	}

	@Override
	public void toAdmin(Message message, SessionID sessionId) {
		// Inject password into Logon message (tag 554)
		try {
			if (message.getHeader().getString(35).equals("A")) {
				message.setString(554, password);
				message.setInt(553, 10645144);
			}
		} catch (FieldNotFound e) {
		}
		System.out.println("toAdmin: " + message);
	}

	@Override
	public void fromAdmin(Message message, SessionID sessionId) {
		System.out.println("fromAdmin: " + message);
	}

	@Override
	public void toApp(Message message, SessionID sessionId) {
		System.out.println("toApp: " + message);
	}

	@Override
	public void fromApp(Message message, SessionID sessionId) {
		System.out.println("fromApp: " + message);
	}

	public static void main(String[] args) throws Exception {
		String password = System.getenv("FXPRO_FIX_PASSWORD");
		if (password == null) {
			System.err.println("Set FXPRO_FIX_PASSWORD env var");
			System.exit(1);
		}

		String configFile = args.length > 0 ? args[0] : "fix-quote-session.cfg";

		SessionSettings settings = new SessionSettings(new FileInputStream(configFile));
		FixLogonTest app = new FixLogonTest(password);
		MessageStoreFactory storeFactory = new FileStoreFactory(settings);
		LogFactory logFactory = new FileLogFactory(settings);
		MessageFactory messageFactory = new DefaultMessageFactory();

		SocketInitiator initiator = new SocketInitiator(
				app, storeFactory, settings, logFactory, messageFactory);

		initiator.start();

		System.out.println("Initiator started. Waiting for logon...");
		Thread.sleep(30_000);

		initiator.stop();
	}
}
