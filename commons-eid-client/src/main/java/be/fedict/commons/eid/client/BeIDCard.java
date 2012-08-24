/*
 * Commons eID Project.
 * Copyright (C) 2008-2012 FedICT.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, see 
 * http://www.gnu.org/licenses/.
 */

package be.fedict.commons.eid.client;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import javax.smartcardio.ATR;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import be.fedict.commons.eid.client.event.BeIDCardListener;
import be.fedict.commons.eid.client.impl.BeIDDigest;
import be.fedict.commons.eid.client.impl.CCID;
import be.fedict.commons.eid.client.impl.VoidLogger;
import be.fedict.commons.eid.client.spi.BeIDCardUI;
import be.fedict.commons.eid.client.spi.Logger;

public class BeIDCard {
	private static final String UI_MISSING_LOG_MESSAGE = "No BeIDCardUI set and can't load DefaultBeIDCardUI";
	private static final String UI_DEFAULT_REQUIRES_HEAD = "No BeIDCardUI set and DefaultBeIDCardUI requires a graphical environment";
	private static final String DEFAULT_UI_IMPLEMENTATION = "be.fedict.eid.commons.dialogs.DefaultBeIDCardUI";

	private static final byte[] BELPIC_AID = new byte[]{(byte) 0xA0, 0x00,
			0x00, 0x01, 0x77, 0x50, 0x4B, 0x43, 0x53, 0x2D, 0x31, 0x35};
	private static final byte[] APPLET_AID = new byte[]{(byte) 0xA0, 0x00,
			0x00, 0x00, 0x30, 0x29, 0x05, 0x70, 0x00, (byte) 0xAD, 0x13, 0x10,
			0x01, 0x01, (byte) 0xFF};
	private static final int BLOCK_SIZE = 0xff;

	private final CardChannel cardChannel;
	private final List<BeIDCardListener> cardListeners;
	private final CertificateFactory certificateFactory;

	private final Card card;
	private final Logger logger;

	private CCID ccid;
	private BeIDCardUI ui;
	private Locale locale;

	public BeIDCard(Card card, Logger logger) {
		this.card = card;
		this.cardChannel = card.getBasicChannel();
		if (null == logger) {
			throw new IllegalArgumentException("logger expected");
		}
		this.logger = logger;
		this.cardListeners = new LinkedList<BeIDCardListener>();
		try {
			this.certificateFactory = CertificateFactory.getInstance("X.509");
		} catch (CertificateException e) {
			throw new RuntimeException("X.509 algo", e);
		}
	}

	public BeIDCard(Card card) {
		this(card, new VoidLogger());
	}

	public BeIDCard(CardTerminal cardTerminal, Logger logger)
			throws CardException {
		this(cardTerminal.connect("T=0"), logger);
	}

	public BeIDCard(CardTerminal cardTerminal) throws CardException {
		this(cardTerminal.connect("T=0"));
	}

	/*
	 * Getting Certificates (by FileType)
	 */

	public X509Certificate getCertificate(FileType fileType)
			throws CertificateException, CardException, IOException,
			InterruptedException {
		return (X509Certificate) this.certificateFactory
				.generateCertificate(new ByteArrayInputStream(
						readFile(fileType)));
	}

	/*
	 * Getting Certificates(convenience methods)
	 */

	public X509Certificate getAuthenticationCertificate() throws CardException,
			IOException, CertificateException, InterruptedException {
		return getCertificate(FileType.AuthentificationCertificate);
	}

	public X509Certificate getSigningCertificate() throws CardException,
			IOException, CertificateException, InterruptedException {
		return getCertificate(FileType.NonRepudiationCertificate);
	}

	public X509Certificate getCACertificate() throws CardException,
			IOException, CertificateException, InterruptedException {
		return getCertificate(FileType.CACertificate);
	}

	public X509Certificate getRRNCertificate() throws CardException,
			IOException, CertificateException, InterruptedException {
		return getCertificate(FileType.RRNCertificate);
	}

	/*
	 * Getting Certificate Chains(by FileType of Leaf Certificate)
	 */

	public List<X509Certificate> getCertificateChain(FileType fileType)
			throws CertificateException, CardException, IOException,
			InterruptedException {
		CertificateFactory certificateFactory = CertificateFactory
				.getInstance("X.509");
		List<X509Certificate> chain = new LinkedList<X509Certificate>();
		chain.add((X509Certificate) certificateFactory
				.generateCertificate(new ByteArrayInputStream(
						readFile(fileType))));
		if (fileType.chainIncludesCitizenCA())
			chain.add((X509Certificate) certificateFactory
					.generateCertificate(new ByteArrayInputStream(
							readFile(FileType.CACertificate))));
		chain.add((X509Certificate) certificateFactory
				.generateCertificate(new ByteArrayInputStream(
						readFile(FileType.RootCertificate))));
		return chain;
	}

	/*
	 * Getting Certificate Chains (convenience methods)
	 */

	public List<X509Certificate> getAuthenticationCertificateChain()
			throws CardException, IOException, CertificateException,
			InterruptedException {
		return getCertificateChain(FileType.AuthentificationCertificate);
	}

	public List<X509Certificate> getSigningCertificateChain()
			throws CardException, IOException, CertificateException,
			InterruptedException {
		return getCertificateChain(FileType.NonRepudiationCertificate);
	}

	public List<X509Certificate> getCACertificateChain() throws CardException,
			IOException, CertificateException, InterruptedException {
		return getCertificateChain(FileType.CACertificate);
	}

	public List<X509Certificate> getRRNCertificateChain() throws CardException,
			IOException, CertificateException, InterruptedException {
		return getCertificateChain(FileType.RRNCertificate);
	}

	/*
	 * Signing data
	 */

	public byte[] sign(byte[] digestValue, BeIDDigest digestAlgo,
			FileType fileType, boolean requireSecureReader)
			throws CardException, IOException, InterruptedException {
		if (!fileType.isCertificateUserCanSignWith())
			throw new IllegalArgumentException(
					"Not a certificate that can be used for signing: "
							+ fileType.name());

		if (getCCID().hasFeature(CCID.FEATURE.EID_PIN_PAD_READER)) {
			this.logger.debug("eID-aware secure PIN pad reader detected");
		}

		if (requireSecureReader
				&& (!getCCID().hasFeature(CCID.FEATURE.VERIFY_PIN_DIRECT))
				&& (getCCID().hasFeature(CCID.FEATURE.VERIFY_PIN_START))) {
			throw new SecurityException("not a secure reader");
		}

		notifySigningBegin(fileType);

		try {
			// select the key
			this.logger.debug("selecting key...");

			ResponseAPDU responseApdu = transmitCommand(
					BeIDCommandAPDU.SELECT_ALGORITHM_AND_PRIVATE_KEY,
					new byte[]{(byte) 0x04, // length
							// of
							// following
							// data
							(byte) 0x80, digestAlgo.getAlgorithmReference(), // algorithm reference
							(byte) 0x84, fileType.getKeyId()}); // private key reference

			if (0x9000 != responseApdu.getSW()) {
				throw new ResponseAPDUException(
						"SET (select algorithm and private key) error",
						responseApdu);
			}

			if (FileType.NonRepudiationCertificate.getKeyId() == fileType
					.getKeyId()) {
				this.logger
						.debug("non-repudiation key detected, immediate PIN verify");
				verifyPin(PINPurpose.NonRepudiationSignature);
			}

			ByteArrayOutputStream digestInfo = new ByteArrayOutputStream();
			digestInfo.write(digestAlgo.getPrefix(digestValue.length));
			digestInfo.write(digestValue);

			this.logger.debug("computing digital signature...");
			responseApdu = transmitCommand(
					BeIDCommandAPDU.COMPUTE_DIGITAL_SIGNATURE, digestInfo
							.toByteArray());
			if (0x9000 == responseApdu.getSW()) {
				/*
				 * OK, we could use the card PIN caching feature.
				 * 
				 * Notice that the card PIN caching also works when first doing an
				 * authentication after a non-repudiation signature.
				 */
				return responseApdu.getData();
			}
			if (0x6982 != responseApdu.getSW()) {
				this.logger.debug("SW: "
						+ Integer.toHexString(responseApdu.getSW()));
				throw new ResponseAPDUException(
						"compute digital signature error", responseApdu);
			}
			/*
			 * 0x6982 = Security status not satisfied, so we do a PIN verification
			 * before retrying.
			 */
			this.logger.debug("PIN verification required...");
			verifyPin(PINPurpose.fromFileType(fileType));

			this.logger
					.debug("computing digital signature (attempt #2 after PIN verification)...");
			responseApdu = transmitCommand(
					BeIDCommandAPDU.COMPUTE_DIGITAL_SIGNATURE, digestInfo
							.toByteArray());
			if (0x9000 != responseApdu.getSW()) {
				throw new ResponseAPDUException(
						"compute digital signature error", responseApdu);
			}

			return responseApdu.getData();
		} finally {
			notifySigningEnd(fileType);
		}
	}

	/*
	 * sign SHA-1 With Authentication key convenience method
	 */

	public byte[] signAuthn(byte[] toBeSigned, boolean requireSecureReader)
			throws NoSuchAlgorithmException, CardException, IOException,
			InterruptedException {
		MessageDigest messageDigest = BeIDDigest.SHA_1
				.getMessageDigestInstance();
		byte[] digest = messageDigest.digest(toBeSigned);
		return sign(digest, BeIDDigest.SHA_1,
				FileType.AuthentificationCertificate, requireSecureReader);
	}

	/*
	 * Verifying PIN Code (without other actions, for testing PIN), using the most secure method available
	 * Note that this still has the side effect of loading a successfully tests PIN into the PIN cache, so that unless the card is removed,
	 * a subsequent authentication attempt will not request the PIN, but proceed with the PIN given here.
	 */

	public void verifyPin() throws IOException, CardException,
			InterruptedException {
		verifyPin(PINPurpose.PINTest);
	}

	/*
	 * Change PIN code
	 * This method will attempt to change PIN using the most secure method available.
	 * if requiresSecureReader is true, this will throw a SecurityException if no SPR is available,
	 * otherwise, this will default to changing the PIN via the UI
	 */

	public void changePin(boolean requireSecureReader) throws Exception {
		if (requireSecureReader
				&& (!getCCID().hasFeature(CCID.FEATURE.MODIFY_PIN_DIRECT))
				&& (!getCCID().hasFeature(CCID.FEATURE.MODIFY_PIN_START))) {
			throw new SecurityException("not a secure reader");
		}

		int retriesLeft = -1;
		ResponseAPDU responseApdu;
		do {
			if (getCCID().hasFeature(CCID.FEATURE.MODIFY_PIN_START)) {
				this.logger.debug("using modify pin start/finish...");
				responseApdu = changePINViaCCIDStartFinish(retriesLeft);
			} else if (getCCID().hasFeature(CCID.FEATURE.MODIFY_PIN_DIRECT)) {
				this.logger.debug("could use direct PIN modify here...");
				responseApdu = changePINViaCCIDDirect(retriesLeft);
			} else {
				responseApdu = changePINViaUI(retriesLeft);
			}

			if (0x9000 != responseApdu.getSW()) {
				this.logger.debug("CHANGE PIN error");
				this.logger.debug("SW: "
						+ Integer.toHexString(responseApdu.getSW()));
				if (0x6983 == responseApdu.getSW()) {
					getUI().advisePINBlocked();
					throw new ResponseAPDUException("eID card blocked!",
							responseApdu);
				}
				if (0x63 != responseApdu.getSW1()) {
					this.logger.debug("PIN change error. Card blocked?");
					throw new ResponseAPDUException("PIN Change Error",
							responseApdu);
				}
				retriesLeft = responseApdu.getSW2() & 0xf;
				this.logger.debug("retries left: " + retriesLeft);
			}
		} while (0x9000 != responseApdu.getSW());
		getUI().advisePINChanged();
	}

	/*
	 * 
	 */

	public byte[] getChallenge(int size) throws CardException {
		ResponseAPDU responseApdu = transmitCommand(
				BeIDCommandAPDU.GET_CHALLENGE, new byte[]{}, 0, 0, size);
		if (0x9000 != responseApdu.getSW()) {
			this.logger.debug("get challenge failure: "
					+ Integer.toHexString(responseApdu.getSW()));
			throw new ResponseAPDUException("get challenge failure: "
					+ Integer.toHexString(responseApdu.getSW()), responseApdu);
		}
		if (size != responseApdu.getData().length) {
			throw new RuntimeException("challenge size incorrect: "
					+ responseApdu.getData().length);
		}
		return responseApdu.getData();
	}

	/*
	 * 
	 */

	public byte[] signTransactionMessage(String transactionMessage,
			boolean requireSecureReader) throws CardException, IOException,
			InterruptedException {
		if (getCCID().hasFeature(CCID.FEATURE.EID_PIN_PAD_READER)) {
			getUI().adviseSecureReaderOperation();
		}

		byte[] signature;
		try {
			signature = sign(transactionMessage.getBytes(),
					BeIDDigest.PLAIN_TEXT,
					FileType.AuthentificationCertificate, requireSecureReader);
		} finally {
			if (getCCID().hasFeature(CCID.FEATURE.EID_PIN_PAD_READER)) {
				getUI().adviseSecureReaderOperationEnd();
			}
		}
		return signature;
	}

	/*
	 * returns this card's ATR
	 */

	public ATR getATR() {
		return this.card.getATR();
	}

	/*
	 * Unblocking PIN using PUKs. This will choose the most secure method available
	 * to unblock a blocked PIN. If requireSecureReader is true, will throw SecurityException
	 * if an SPR is not available
	 */

	public void unblockPin(boolean requireSecureReader) throws Exception {
		if (requireSecureReader
				&& (!getCCID().hasFeature(CCID.FEATURE.VERIFY_PIN_DIRECT))) {
			throw new SecurityException("not a secure reader");
		}

		ResponseAPDU responseApdu;
		int retriesLeft = -1;
		do {
			if (getCCID().hasFeature(CCID.FEATURE.VERIFY_PIN_DIRECT)) {
				this.logger.debug("could use direct PIN verify here...");
				responseApdu = unblockPINViaCCIDVerifyPINDirectOfPUK(retriesLeft);
			} else {
				responseApdu = unblockPINViaUI(retriesLeft);
			}

			if (0x9000 != responseApdu.getSW()) {
				this.logger.debug("PIN unblock error");
				this.logger.debug("SW: "
						+ Integer.toHexString(responseApdu.getSW()));
				if (0x6983 == responseApdu.getSW()) {
					getUI().advisePINBlocked();
					throw new ResponseAPDUException("eID card blocked!",
							responseApdu);
				}
				if (0x63 != responseApdu.getSW1()) {
					this.logger.debug("PIN unblock error.");
					throw new ResponseAPDUException("PIN unblock error",
							responseApdu);
				}
				retriesLeft = responseApdu.getSW2() & 0xf;
				this.logger.debug("retries left: " + retriesLeft);
			}
		} while (0x9000 != responseApdu.getSW());
		getUI().advisePINUnblocked();
	}

	//===========================================================================================================
	// private implementations
	//===========================================================================================================

	/*
	 * Verify PIN code for purpose "purpose"
	 * This method will attempt to verify PIN using the most secure method available. If that method
	 * turns out to be the UI, will pass purpose to the UI.
	 */

	private void verifyPin(PINPurpose purpose) throws IOException,
			CardException, InterruptedException {
		ResponseAPDU responseApdu;
		int retriesLeft = -1;
		do {
			if (getCCID().hasFeature(CCID.FEATURE.VERIFY_PIN_DIRECT)) {
				responseApdu = verifyPINViaCCIDDirect(retriesLeft);
			} else if (getCCID().hasFeature(CCID.FEATURE.VERIFY_PIN_START)) {
				responseApdu = verifyPINViaCCIDStartFinish(retriesLeft);
			} else {
				responseApdu = verifyPINViaUI(retriesLeft, purpose);
			}

			if (0x9000 != responseApdu.getSW()) {
				this.logger.debug("VERIFY_PIN error");
				this.logger.debug("SW: "
						+ Integer.toHexString(responseApdu.getSW()));
				if (0x6983 == responseApdu.getSW()) {
					getUI().advisePINBlocked();
					throw new ResponseAPDUException("eID card blocked!",
							responseApdu);
				}
				if (0x63 != responseApdu.getSW1()) {
					this.logger.debug("PIN verification error.");
					throw new ResponseAPDUException("PIN Verification Error",
							responseApdu);
				}
				retriesLeft = responseApdu.getSW2() & 0xf;
				this.logger.debug("retries left: " + retriesLeft);
			}
		} while (0x9000 != responseApdu.getSW());
	}

	/*
	 * Verify PIN code using CCID Direct PIN Verify sequence.
	 */

	private ResponseAPDU verifyPINViaCCIDDirect(int retriesLeft)
			throws IOException, CardException {
		this.logger.debug("direct PIN verification...");
		getUI().advisePINPadPINEntry(retriesLeft);
		byte[] result;
		try {
			result = transmitCCIDControl(CCID.FEATURE.VERIFY_PIN_DIRECT,
					getCCID().createPINVerificationDataStructure(getLocale(),
							CCID.INS.VERIFY_PIN));
		} finally {
			getUI().advisePINPadOperationEnd();
		}
		ResponseAPDU responseApdu = new ResponseAPDU(result);
		if (0x6401 == responseApdu.getSW()) {
			this.logger.debug("canceled by user");
			SecurityException securityException = new SecurityException(
					"canceled by user");
			securityException
					.initCause(new ResponseAPDUException(responseApdu));
			throw securityException;
		} else if (0x6400 == responseApdu.getSW()) {
			this.logger.debug("PIN pad timeout");
		}
		return responseApdu;
	}

	/*
	 * Verify PIN code using CCID Start/Finish sequence.
	 */

	private ResponseAPDU verifyPINViaCCIDStartFinish(int retriesLeft)
			throws IOException, CardException, InterruptedException {
		this.logger.debug("CCID verify PIN start/end sequence...");

		getUI().advisePINPadPINEntry(retriesLeft);

		try {
			transmitCCIDControl(CCID.FEATURE.GET_KEY_PRESSED, getCCID()
					.createPINVerificationDataStructure(getLocale(),
							CCID.INS.VERIFY_PIN));
			getCCID().waitForOK();
		} finally {
			getUI().advisePINPadOperationEnd();
		}

		return new ResponseAPDU(
				transmitCCIDControl(CCID.FEATURE.VERIFY_PIN_FINISH));
	}

	/*
	 * Verify PIN code by obtaining it from the current UI
	 */

	private ResponseAPDU verifyPINViaUI(int retriesLeft, PINPurpose purpose)
			throws CardException {
		char[] pin = getUI().obtainPIN(retriesLeft, purpose);
		byte[] verifyData = new byte[]{(byte) (0x20 | pin.length), (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF};
		for (int idx = 0; idx < pin.length; idx += 2) {
			char digit1 = pin[idx];
			char digit2;
			if (idx + 1 < pin.length) {
				digit2 = pin[idx + 1];
			} else {
				digit2 = '0' + 0xf;
			}
			byte value = (byte) (byte) ((digit1 - '0' << 4) + (digit2 - '0'));
			verifyData[idx / 2 + 1] = value;
		}
		Arrays.fill(pin, (char) 0); // minimize exposure

		this.logger.debug("verifying PIN...");
		try {
			return transmitCommand(BeIDCommandAPDU.VERIFY_PIN, verifyData);
		} finally {
			Arrays.fill(verifyData, (byte) 0); // minimize exposure
		}
	}

	/*
	 * Modify PIN code using CCID Direct PIN Modify sequence.
	 */

	private ResponseAPDU changePINViaCCIDDirect(int retriesLeft)
			throws IOException, CardException {
		this.logger.debug("direct PIN modification...");
		getUI().advisePINPadChangePIN(retriesLeft);
		byte[] result;

		try {
			result = transmitCCIDControl(CCID.FEATURE.MODIFY_PIN_DIRECT,
					getCCID().createPINModificationDataStructure(getLocale(),
							CCID.INS.MODIFY_PIN));
		} finally {
			getUI().advisePINPadOperationEnd();
		}

		ResponseAPDU responseApdu = new ResponseAPDU(result);
		if (0x6402 == responseApdu.getSW()) {
			this.logger.debug("PINs differ");
		} else if (0x6401 == responseApdu.getSW()) {
			this.logger.debug("canceled by user");
			SecurityException securityException = new SecurityException(
					"canceled by user");
			securityException
					.initCause(new ResponseAPDUException(responseApdu));
			throw securityException;
		} else if (0x6400 == responseApdu.getSW()) {
			this.logger.debug("PIN pad timeout");
		}

		return responseApdu;
	}

	/*
	 * Modify PIN code using CCID Modify PIN Start sequence
	 */

	private ResponseAPDU changePINViaCCIDStartFinish(int retriesLeft)
			throws IOException, CardException, InterruptedException {
		transmitCCIDControl(CCID.FEATURE.MODIFY_PIN_START, getCCID()
				.createPINModificationDataStructure(getLocale(),
						CCID.INS.MODIFY_PIN));

		try {
			this.logger.debug("enter old PIN...");
			getUI().advisePINPadOldPINEntry(retriesLeft);
			getCCID().waitForOK();
			getUI().advisePINPadOperationEnd();

			this.logger.debug("enter new PIN...");
			getUI().advisePINPadNewPINEntry(retriesLeft);
			getCCID().waitForOK();
			getUI().advisePINPadOperationEnd();

			this.logger.debug("enter new PIN again...");
			getUI().advisePINPadNewPINEntryAgain(retriesLeft);
			getCCID().waitForOK();
		} finally {
			getUI().advisePINPadOperationEnd();
		}

		return new ResponseAPDU(
				transmitCCIDControl(CCID.FEATURE.MODIFY_PIN_FINISH));
	}

	/*
	 * Modify PIN via the UI
	 */

	private ResponseAPDU changePINViaUI(int retriesLeft) throws CardException {
		char[][] pins = getUI().obtainOldAndNewPIN(retriesLeft);
		char[] oldPin = pins[0];
		char[] newPin = pins[1];

		byte[] changePinData = new byte[]{(byte) (0x20 | oldPin.length),
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
				(byte) (0x20 | newPin.length), (byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

		for (int idx = 0; idx < oldPin.length; idx += 2) {
			char digit1 = oldPin[idx];
			char digit2;
			if (idx + 1 < oldPin.length) {
				digit2 = oldPin[idx + 1];
			} else {
				digit2 = '0' + 0xf;
			}
			byte value = (byte) (byte) ((digit1 - '0' << 4) + (digit2 - '0'));
			changePinData[idx / 2 + 1] = value;
		}
		Arrays.fill(oldPin, (char) 0); // minimize exposure

		for (int idx = 0; idx < newPin.length; idx += 2) {
			char digit1 = newPin[idx];
			char digit2;
			if (idx + 1 < newPin.length) {
				digit2 = newPin[idx + 1];
			} else {
				digit2 = '0' + 0xf;
			}
			byte value = (byte) (byte) ((digit1 - '0' << 4) + (digit2 - '0'));
			changePinData[(idx / 2 + 1) + 8] = value;
		}
		Arrays.fill(newPin, (char) 0); // minimize exposure

		try {
			return transmitCommand(BeIDCommandAPDU.CHANGE_PIN, changePinData);
		} finally {
			Arrays.fill(changePinData, (byte) 0);
		}
	}

	/*
	 * Unblock PIN using CCID Verify PIN Direct sequence on the PUK
	 */

	private ResponseAPDU unblockPINViaCCIDVerifyPINDirectOfPUK(int retriesLeft)
			throws IOException, CardException {
		this.logger.debug("direct PUK verification...");
		getUI().advisePINPadPUKEntry(retriesLeft);
		byte[] result;
		try {
			result = transmitCCIDControl(CCID.FEATURE.VERIFY_PIN_DIRECT,
					getCCID().createPINVerificationDataStructure(getLocale(),
							CCID.INS.VERIFY_PUK));
		} finally {
			getUI().advisePINPadOperationEnd();
		}

		ResponseAPDU responseApdu = new ResponseAPDU(result);
		if (0x6401 == responseApdu.getSW()) {
			this.logger.debug("canceled by user");
			SecurityException securityException = new SecurityException(
					"canceled by user");
			securityException
					.initCause(new ResponseAPDUException(responseApdu));
			throw securityException;
		} else if (0x6400 == responseApdu.getSW()) {
			this.logger.debug("PIN pad timeout");
		}
		return responseApdu;
	}

	/*
	 * Unblock the PIN by obtaining PUK codes from the UI and calling RESET_PIN on the card.
	 */

	private ResponseAPDU unblockPINViaUI(int retriesLeft) throws CardException {
		char[][] puks = getUI().obtainPUKCodes(retriesLeft);
		char[] puk1 = puks[0];
		char[] puk2 = puks[1];

		char[] fullPuk = new char[puks.length];
		System.arraycopy(puk2, 0, fullPuk, 0, puk2.length);
		Arrays.fill(puk2, (char) 0);
		System.arraycopy(puk1, 0, fullPuk, puk2.length, puk1.length);
		Arrays.fill(puk1, (char) 0);

		byte[] unblockPinData = new byte[]{
				(byte) (0x20 | ((byte) (puks.length))), (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF};

		for (int idx = 0; idx < fullPuk.length; idx += 2) {
			char digit1 = fullPuk[idx];
			char digit2 = fullPuk[idx + 1];
			byte value = (byte) (byte) ((digit1 - '0' << 4) + (digit2 - '0'));
			unblockPinData[idx / 2 + 1] = value;
		}
		Arrays.fill(fullPuk, (char) 0); // minimize exposure

		try {
			return transmitCommand(BeIDCommandAPDU.RESET_PIN, unblockPinData);
		} finally {
			Arrays.fill(unblockPinData, (byte) 0);
		}
	}

	public Locale getLocale() {
		if (this.locale != null)
			return this.locale;
		return Locale.getDefault();
	}

	public void setLocale(Locale locale) {
		this.locale = locale;
	}

	public BeIDCard addCardListener(BeIDCardListener beIDCardListener) {
		synchronized (this.cardListeners) {
			this.cardListeners.add(beIDCardListener);
		}

		return this;
	}

	public BeIDCard removeCardListener(BeIDCardListener beIDCardListener) {
		synchronized (this.cardListeners) {
			this.cardListeners.remove(beIDCardListener);
		}

		return this;
	}

	public BeIDCard selectApplet() throws CardException {
		ResponseAPDU responseApdu;

		responseApdu = transmitCommand(BeIDCommandAPDU.SELECT_APPLET_0,
				BELPIC_AID);
		if (0x9000 != responseApdu.getSW()) {
			logger.error("error selecting BELPIC");
			logger.debug("status word: "
					+ Integer.toHexString(responseApdu.getSW()));
			/*
			 * Try to select the Applet.
			 */
			try {
				responseApdu = transmitCommand(BeIDCommandAPDU.SELECT_APPLET_1,
						APPLET_AID);
			} catch (CardException e) {
				logger.error("error selecting Applet");
				return this;
			}
			if (0x9000 != responseApdu.getSW()) {
				logger.error("could not select applet");
			} else {
				logger.debug("BELPIC JavaCard applet selected by APPLET_AID");
			}
		} else {
			logger.debug("BELPIC JavaCard applet selected by BELPIC_AID");
		}

		return this;
	}

	// --------------------------------------------------------------------------------------------------------------------------------

	public BeIDCard beginExclusive() throws CardException {
		this.logger.debug("---begin exclusive---");
		this.card.beginExclusive();
		return this;
	}

	public BeIDCard endExclusive() throws CardException {
		this.logger.debug("---end exclusive---");
		this.card.endExclusive();
		return this;
	}

	// --------------------------------------------------------------------------------------------------------------------------------

	public byte[] readBinary(FileType fileType, int estimatedMaxSize)
			throws CardException, IOException, InterruptedException {
		int offset = 0;
		this.logger.debug("read binary");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] data;
		do {
			if (Thread.currentThread().isInterrupted()) {
				logger.debug("interrupted in readBinary");
				throw new InterruptedException();
			}

			notifyReadProgress(fileType, offset, estimatedMaxSize);
			ResponseAPDU responseApdu = transmitCommand(
					BeIDCommandAPDU.READ_BINARY, offset >> 8, offset & 0xFF,
					BLOCK_SIZE);
			int sw = responseApdu.getSW();
			if (0x6B00 == sw) {
				/*
				 * Wrong parameters (offset outside the EF) End of file reached.
				 * Can happen in case the file size is a multiple of 0xff bytes.
				 */
				break;
			}

			if (0x9000 != sw) {
				IOException ioEx = new IOException(
						"BeIDCommandAPDU response error: "
								+ responseApdu.getSW());
				ioEx.initCause(new ResponseAPDUException(responseApdu));
				throw ioEx;
			}

			data = responseApdu.getData();
			baos.write(data);
			offset += data.length;
		} while (BLOCK_SIZE == data.length);
		notifyReadProgress(fileType, offset, offset);
		return baos.toByteArray();
	}

	public BeIDCard selectFile(byte[] fileId) throws CardException,
			FileNotFoundException {
		this.logger.debug("selecting file");
		ResponseAPDU responseApdu = transmitCommand(
				BeIDCommandAPDU.SELECT_FILE, fileId);
		if (0x9000 != responseApdu.getSW()) {
			FileNotFoundException fnfEx = new FileNotFoundException(
					"wrong status word after selecting file: "
							+ Integer.toHexString(responseApdu.getSW()));
			fnfEx.initCause(new ResponseAPDUException(responseApdu));
			throw fnfEx;
		}

		try {
			// SCARD_E_SHARING_VIOLATION fix
			Thread.sleep(20);
		} catch (InterruptedException e) {
			throw new RuntimeException("sleep error: " + e.getMessage());
		}

		return this;
	}

	public byte[] readFile(FileType fileType) throws CardException,
			IOException, InterruptedException {
		beginExclusive();

		try {
			selectFile(fileType.getFileId());
			return readBinary(fileType, fileType.getEstimatedMaxSize());
		} finally {
			endExclusive();
		}
	}

	public BeIDCard close() {
		this.logger.debug("closing eID card");

		try {
			this.card.disconnect(true);
		} catch (CardException e) {
			this.logger.error("could not disconnect the card: "
					+ e.getMessage());
		}

		return this;
	}

	public void logoff() throws Exception {
		CommandAPDU logoffApdu = new CommandAPDU(0x80, 0xE6, 0x00, 0x00);
		this.logger.debug("logoff...");
		ResponseAPDU responseApdu = transmit(logoffApdu);
		if (0x9000 != responseApdu.getSW()) {
			throw new RuntimeException("logoff failed");
		}
	}

	// ----------------------------------------------------------------------------------------------------------------------------------

	protected byte[] transmitCCIDControl(CCID.FEATURE feature)
			throws CardException {
		return transmitControlCommand(getCCID().getFeature(feature),
				new byte[0]);
	}

	protected byte[] transmitCCIDControl(CCID.FEATURE feature, byte[] command)
			throws CardException {
		return transmitControlCommand(getCCID().getFeature(feature), command);
	}

	protected byte[] transmitControlCommand(int controlCode, byte[] command)
			throws CardException {
		return this.card.transmitControlCommand(controlCode, command);
	}

	protected ResponseAPDU transmitCommand(BeIDCommandAPDU apdu, int p1,
			int p2, int le) throws CardException {
		return transmit(new CommandAPDU(apdu.getCla(), apdu.getIns(), p1, p2,
				le));
	}

	protected ResponseAPDU transmitCommand(BeIDCommandAPDU apdu, byte[] data)
			throws CardException {
		return transmit(new CommandAPDU(apdu.getCla(), apdu.getIns(), apdu
				.getP1(), apdu.getP2(), data));
	}

	protected ResponseAPDU transmitCommand(BeIDCommandAPDU apdu, byte[] data,
			int dataOffset, int dataLength, int ne) throws CardException {
		return transmit(new CommandAPDU(apdu.getCla(), apdu.getIns(), apdu
				.getP1(), apdu.getP2(), data, dataOffset, dataLength, ne));
	}

	private ResponseAPDU transmit(CommandAPDU commandApdu) throws CardException {
		ResponseAPDU responseApdu = this.cardChannel.transmit(commandApdu);
		if (0x6c == responseApdu.getSW1()) {
			/*
			 * A minimum delay of 10 msec between the answer ?????????6C
			 * xx????????? and the next BeIDCommandAPDU is mandatory for eID
			 * v1.0 and v1.1 cards.
			 */
			this.logger.debug("sleeping...");
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				throw new RuntimeException("cannot sleep");
			}
			responseApdu = this.cardChannel.transmit(commandApdu);
		}
		return responseApdu;
	}

	// ----------------------------------------------------------------------------------------------------------------------------------

	private void notifyReadProgress(FileType fileType, int offset,
			int estimatedMaxOffset) {
		if (offset > estimatedMaxOffset) {
			estimatedMaxOffset = offset;
		}

		synchronized (this.cardListeners) {
			for (BeIDCardListener listener : this.cardListeners) {
				try {
					listener.notifyReadProgress(fileType, offset,
							estimatedMaxOffset);
				} catch (Exception ex) {
					this.logger
							.debug("Exception Thrown In BeIDCardListener.notifyReadProgress():"
									+ ex.getMessage());
				}
			}
		}
	}

	private void notifySigningBegin(FileType keyType) {
		synchronized (this.cardListeners) {
			for (BeIDCardListener listener : this.cardListeners) {
				try {
					listener.notifySigningBegin(keyType);
				} catch (Exception ex) {
					this.logger
							.debug("Exception Thrown In BeIDCardListener.notifySigningBegin():"
									+ ex.getMessage());
				}
			}
		}
	}

	private void notifySigningEnd(FileType keyType) {
		synchronized (this.cardListeners) {
			for (BeIDCardListener listener : this.cardListeners) {
				try {
					listener.notifySigningEnd(keyType);
				} catch (Exception ex) {
					this.logger
							.debug("Exception Thrown In BeIDCardListener.notifySigningBegin():"
									+ ex.getMessage());
				}
			}
		}
	}

	// ----------------------------------------------------------------------------------------------------------------------------------

	private CCID getCCID() {
		if (this.ccid == null)
			this.ccid = new CCID(this.card);
		return this.ccid;
	}

	private BeIDCardUI getUI() {
		if (this.ui == null) {
			if (GraphicsEnvironment.isHeadless()) {
				logger.error(UI_DEFAULT_REQUIRES_HEAD);
				throw new UnsupportedOperationException(
						UI_DEFAULT_REQUIRES_HEAD);
			}

			try {
				ClassLoader classLoader = BeIDCard.class.getClassLoader();
				Class<?> uiClass = classLoader
						.loadClass(DEFAULT_UI_IMPLEMENTATION);
				this.ui = (BeIDCardUI) uiClass.newInstance();
			} catch (Exception e) {
				logger.error(UI_MISSING_LOG_MESSAGE);
				throw new UnsupportedOperationException(UI_MISSING_LOG_MESSAGE,
						e);
			}
		}

		return this.ui;
	}

	public void setUI(BeIDCardUI userInterface) {
		this.ui = userInterface;
	}

	/*
	 * BeIDCommandAPDU encapsulates values sent in CommandAPDU's, to make these
	 * more readable in BeIDCard.
	 */

	private enum BeIDCommandAPDU {
		SELECT_APPLET_0(0x00, 0xA4, 0x04, 0x0C),

		SELECT_APPLET_1(0x00, 0xA4, 0x04, 0x0C),

		SELECT_FILE(0x00, 0xA4, 0x08, 0x0C),

		READ_BINARY(0x00, 0xB0),

		VERIFY_PIN(0x00, 0x20, 0x00, 0x01),

		CHANGE_PIN(0x00, 0x24, 0x00, 0x01), // 0x0024=change
		// reference
		// change
		SELECT_ALGORITHM_AND_PRIVATE_KEY(0x00, 0x22, 0x41, 0xB6), // ISO 7816-8 SET
		// COMMAND
		// (select
		// algorithm and
		// key for
		// signature)

		COMPUTE_DIGITAL_SIGNATURE(0x00, 0x2A, 0x9E, 0x9A), // ISO 7816-8 COMPUTE
		// DIGITAL SIGNATURE
		// COMMAND
		RESET_PIN(0x00, 0x2C, 0x00, 0x01),

		GET_CHALLENGE(0x00, 0x84, 0x00, 0x00);

		private final int cla;
		private final int ins;
		private final int p1;
		private final int p2;

		private BeIDCommandAPDU(int cla, int ins, int p1, int p2) {
			this.cla = cla;
			this.ins = ins;
			this.p1 = p1;
			this.p2 = p2;
		}

		private BeIDCommandAPDU(int cla, int ins) {
			this.cla = cla;
			this.ins = ins;
			this.p1 = -1;
			this.p2 = -1;
		}

		public int getCla() {
			return cla;
		}

		public int getIns() {
			return ins;
		}

		public int getP1() {
			return p1;
		}

		public int getP2() {
			return p2;
		}
	}
}
