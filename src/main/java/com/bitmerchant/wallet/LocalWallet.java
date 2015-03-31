package com.bitmerchant.wallet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import javax.annotation.Nullable;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.wallet.DeterministicSeed;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import com.bitmerchant.db.InitializeTables;
import com.bitmerchant.tools.DataSources;
import com.bitmerchant.tools.Tools;
import com.bitmerchant.webservice.WebService;


/**
 * TODO list:
 * - Implement a local wallet using bitcoinj
 * - Wallet should be able to:
 * - Generate addresses
 * - Send and receive bitcoin without having to specify addresses
 * - Create an HTML5 GUI with which to view/send/receive moneys
 * - Do a wizard dialog in bootstrap on first run to set up the wallet, set a password,
 * 	 and then set up your merchant properties
 * - Use base 36 for order numbers, or customer presented number values
 * - sending money, money sent.
 */
public class LocalWallet {

	static  Logger log = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);


	public static NetworkParameters params;
	public static WalletAppKit bitcoin;
	public Controller controller;

	public static LocalWallet INSTANCE = new LocalWallet();


	@Option(name="-testnet",usage="Run using the Bitcoin testnet3")
	private boolean testnet;

	@Option(name="-deleteDB",usage="Delete the sqlite DB before running.")
	private boolean deleteDB;

	@Option(name="-loglevel", usage="Sets the log level [INFO, DEBUG, etc.]")     
	private String loglevel = "INFO";

	public void doMain(String[] args) {


		parseArguments(args);


		log.setLevel(Level.toLevel(loglevel));

		// get the correct network
		params = (testnet) ? TestNet3Params.get() : MainNetParams.get();
		DataSources.HOME_DIR = (testnet) ? DataSources.HOME_DIR  + "/testnet" : DataSources.HOME_DIR;

		setupDirectories();

		copyResourcesToHomeDir();

		// Initialize the DB if it hasn't already
		InitializeTables.init(deleteDB);




		// Start the wallet
		INSTANCE.init();

		// Start the web service
		WebService.start();



		Tools.pollAndOpenStartPage();


	}

	private void parseArguments(String[] args) {
		CmdLineParser parser = new CmdLineParser(this);

		try {

			parser.parseArgument(args);

		} catch (CmdLineException e) {
			// if there's a problem in the command line,
			// you'll get this exception. this will report
			// an error message.
			System.err.println(e.getMessage());
			System.err.println("java LocalWallet [options...] arguments...");
			// print the list of available options
			parser.printUsage(System.err);
			System.err.println();


			return;
		}
	}
	public static void main( String[] args ) {
		new LocalWallet().doMain(args);
	}

	public static void startService(String homePath, String loglevel, 
			Boolean testnet, Boolean deleteDB, Boolean startWebService) {

		log.setLevel(Level.toLevel(loglevel));

		// Set the correct directory
		DataSources.HOME_DIR = homePath;

		// get the correct network
		params = (testnet) ? TestNet3Params.get() : MainNetParams.get();

//		DataSources.HOME_DIR = (testnet) ? DataSources.HOME_DIR  + "/testnet" : DataSources.HOME_DIR;

		setupDirectories();

		//		copyResourcesToHomeDir();

		// Initialize the DB if it hasn't already
		InitializeTables.init(deleteDB);

		// Start the wallet
		INSTANCE.init();

		// Start the web service
		if (startWebService) {
			WebService.start(); 
		}

//		Tools.pollAndOpenStartPage();

	}

	public void init() {


		setupWalletKit(null);


		bitcoin.startAsync();

	}

	public static void setupDirectories() {
		log.info("Setting up " + DataSources.HOME_DIR +" dirs");
		new File(DataSources.HOME_DIR).mkdirs();
	}

	public static void copyResourcesToHomeDir() {
		log.info("Copying resources to  ~/.bitmerchant dirs");

		String zipFile = null;
		try {
			if (new File(DataSources.SHADED_JAR_FILE).exists()) {
				java.nio.file.Files.copy(Paths.get(DataSources.SHADED_JAR_FILE), Paths.get(DataSources.ZIP_FILE()), 
						StandardCopyOption.REPLACE_EXISTING);
				zipFile = DataSources.SHADED_JAR_FILE;

			} else if (new File(DataSources.SHADED_JAR_FILE_2).exists()) {
				java.nio.file.Files.copy(Paths.get(DataSources.SHADED_JAR_FILE_2), Paths.get(DataSources.ZIP_FILE()),
						StandardCopyOption.REPLACE_EXISTING);
				zipFile = DataSources.SHADED_JAR_FILE_2;
			}
		} catch (IOException e) {

			e.printStackTrace();
		}
		Tools.unzip(new File(zipFile), new File(DataSources.SOURCE_CODE_HOME()));
		//		new Tools().copyJarResourcesRecursively("src", configHome);
	}

	public void stop() throws Exception {
		bitcoin.stopAsync();
		bitcoin.awaitTerminated();
		// Forcibly terminate the JVM because Orchid likes to spew non-daemon threads everywhere.
		Runtime.getRuntime().exit(0);
	}

	public void restart() throws Exception {
		bitcoin.stopAsync();
		bitcoin.awaitTerminated();

		Tools.restartApplication();
	}

	public void setupWalletKit(@Nullable DeterministicSeed seed) {
		controller = new Controller();
		// If seed is non-null it means we are restoring from backup.
		bitcoin = new WalletAppKit(params, new File(DataSources.HOME_DIR), DataSources.APP_NAME) {

			@Override
			protected void onSetupCompleted() {
				// Don't make the user wait for confirmations for now, as the intention is they're sending it
				// their own money!
				bitcoin.wallet().allowSpendingUnconfirmedTransactions();
				if (params != RegTestParams.get())
					bitcoin.peerGroup().setMaxConnections(11);
				bitcoin.peerGroup().setBloomFilterFalsePositiveRate(0.00001);
				controller.onBitcoinSetup();
				//                Platform.runLater(controller::onBitcoinSetup);
			}

		};
		// Now configure and start the appkit. This will take a second or two - we could show a temporary splash screen
		// or progress widget to keep the user engaged whilst we initialise, but we don't.
		if (params == RegTestParams.get()) {
			bitcoin.connectToLocalHost();   // You should run a regtest mode bitcoind locally.
		} else if (params == TestNet3Params.get()) {
			// As an example!
			//			bitcoin.useTor();
			// bitcoin.setDiscovery(new HttpDiscovery(params, URI.create("http://localhost:8080/peers"), ECKey.fromPublicOnly(BaseEncoding.base16().decode("02cba68cfd0679d10b186288b75a59f9132b1b3e222f6332717cb8c4eb2040f940".toUpperCase()))));
		}

		// The progress bar stuff

		bitcoin.setDownloadListener(controller.progressBarUpdater())
		.setBlockingStartup(false)
		.setUserAgent(DataSources.APP_NAME, "1.0");

		if (seed != null)
			bitcoin.restoreWalletFromSeed(seed);




	}




}
