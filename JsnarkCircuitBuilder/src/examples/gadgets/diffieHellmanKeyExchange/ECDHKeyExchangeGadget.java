/*******************************************************************************
 * Author: Ahmed Kosba <akosba@cs.umd.edu>
 * Modified for compatibility with Bouncy Castle 1.79
 *******************************************************************************/

 package examples.gadgets.diffieHellmanKeyExchange;

 import java.math.BigInteger;
 
 import org.bouncycastle.math.ec.ECFieldElement;
 import org.bouncycastle.math.ec.custom.sec.SecP256R1FieldElement;
 
 import circuit.config.Config;
 import circuit.eval.CircuitEvaluator;
 import circuit.eval.Instruction;
 import circuit.operations.Gadget;
 import circuit.structure.ConstantWire;
 import circuit.structure.Wire;
 import examples.gadgets.math.FieldDivisionGadget;
 
 /**
  * This gadget implements cryptographic key exchange using a customized elliptic
  * curve that is efficient to represent as a SNARK circuit. It follows the
  * high-level guidelines used for the design of Curve25519, while having the
  * cost model of QAP-based SNARKs in mind. Details in section 6:
  * https://eprint.iacr.org/2015/1093.pdf
  * 
  * Detailed comments about the inputs and outputs of the circuit are below.
  * 
  * Note: By default, this gadget validates only the secret values that are
  * provided by the prover, such as the secret key, and any intermediate
  * auxiliary witnesses that the prover uses in the circuit. In the default mode,
  * the gadget does not check the public input keys, e.g. it does not verify that
  * the base point or the other party's input have the appropriate order, as such
  * inputs could be typically public and can be checked outside the circuit if
  * needed. The Curve25519 paper as well claims that validation is not necessary
  * (although there is debate about some cases online). If any validation is
  * desired, there is a separate method called validateInputs() that do
  * validation, but is not called by default.
  * 
  * 
  * 
  */
 
 public class ECDHKeyExchangeGadget extends Gadget {
 
	 // Note: this parameterization assumes that the underlying field has
	 // Config.FIELD_PRIME =
	 // 21888242871839275222246405745257275088548364400416034343698204186575808495617
 
	 public final static int SECRET_BITWIDTH = 253; // number of bits in the
													 // exponent. Note that the
													 // most significant bit
													 // should
													 // be set to 1, and the
													 // three least significant
													 // bits should be be zero.
													 // See
													 // the constructor
 
	 public final static BigInteger COEFF_A = new BigInteger("126932"); // parameterization
																		 // in
																		 // https://eprint.iacr.org/2015/1093.pdf
 
	 public final static BigInteger CURVE_ORDER = new BigInteger(
			 "21888242871839275222246405745257275088597270486034011716802747351550446453784");
 
	 // As in curve25519, CURVE_ORDER = SUBGROUP_ORDER * 2^3
	 public final static BigInteger SUBGROUP_ORDER = new BigInteger(
			 "2736030358979909402780800718157159386074658810754251464600343418943805806723");
 
	 // The Affine point representation is used as it saves one gate per bit
	 private AffinePoint basePoint; // The Base point both parties agree to
	 private AffinePoint hPoint; // H is the other party's public value
								 // H = (other party's secret)* Base <- scalar EC
								 // multiplication
 
	 private Wire[] secretBits; // the bits of the secret generated by this party
								 // (follows little-endian order)
 
	 // gadget outputs
	 private Wire outputPublicValue; // the x-coordinate of the key exchange
									 // material to be sent to the other party
									 // outputPublicValue = ((this party's
									 // secret)*Base).x
 
	 private Wire sharedSecret; // the x-coordinate of the derived key ((this
								 // party's secret)*H).x
 
	 private AffinePoint[] baseTable;
	 private AffinePoint[] hTable;
 
	 private class AffinePoint {
		 private Wire x;
		 private Wire y;
 
		 AffinePoint(Wire x) {
			 this.x = x;
		 }
 
		 AffinePoint(Wire x, Wire y) {
			 this.x = x;
			 this.y = y;
		 }
 
		 AffinePoint(AffinePoint p) {
			 this.x = p.x;
			 this.y = p.y;
		 }
	 }
 
	 /**
	  * This gadget receives two points: Base = (baseX) and H = (hX), and the
	  * secret key Bits and outputs the scalar EC multiplications: secret*Base,
	  * secret*H
	  * 
	  * The secret key bits must be of length SECRET_BITWIDTH and are expected to
	  * follow a little endian order. The most significant bit should be 1, and
	  * the three least significant bits should be zero.
	  * 
	  * This gadget can work with both static and dynamic inputs If public keys
	  * are static, the wires of base and h should be made ConstantWires when
	  * creating them (before calling this gadget).
	  * 
	  * 
	  */
 
	 public ECDHKeyExchangeGadget(Wire baseX, Wire hX, Wire[] secretBits,
			 String... desc) {
		 super(desc);
		 this.secretBits = secretBits;
		 this.basePoint = new AffinePoint(baseX);
		 this.hPoint = new AffinePoint(hX);
		 checkSecretBits();
		 computeYCoordinates(); // For efficiency reasons, we rely on affine
								 // coordinates
		 buildCircuit();
	 }
 
	 // same constructor as before, but accepts also baseY, and hY as inputs
	 public ECDHKeyExchangeGadget(Wire baseX, Wire baseY, Wire hX, Wire hY,
			 Wire[] secretBits, String... desc) {
		 super(desc);
 
		 this.secretBits = secretBits;
		 this.basePoint = new AffinePoint(baseX, baseY);
		 this.hPoint = new AffinePoint(hX, hY);
		 checkSecretBits();
		 buildCircuit();
	 }
 
	 protected void buildCircuit() {
 
		 /**
		  * The reason this operates on affine coordinates is that in our
		  * setting, this's slightly cheaper than the formulas in
		  * https://cr.yp.to/ecdh/curve25519-20060209.pdf. Concretely, the
		  * following equations save 1 multiplication gate per bit. (we consider
		  * multiplications by constants cheaper in our setting, so they are not
		  * counted)
		  */
 
		 baseTable = preprocess(basePoint);
		 hTable = preprocess(hPoint);
		 outputPublicValue = mul(basePoint, secretBits, baseTable).x;
		 sharedSecret = mul(hPoint, secretBits, hTable).x;
	 }
 
	 private void checkSecretBits() {
		 /**
		  * The secret key bits must be of length SECRET_BITWIDTH and are
		  * expected to follow a little endian order. The most significant bit
		  * should be 1, and the three least significant bits should be zero.
		  */
		 if (secretBits.length != SECRET_BITWIDTH) {
			 throw new IllegalArgumentException();
		 }
		 generator.addZeroAssertion(secretBits[0],
				 "Asserting secret bit conditions");
		 generator.addZeroAssertion(secretBits[1],
				 "Asserting secret bit conditions");
		 generator.addZeroAssertion(secretBits[2],
				 "Asserting secret bit conditions");
		 generator.addOneAssertion(secretBits[SECRET_BITWIDTH - 1],
				 "Asserting secret bit conditions");
 
		 for (int i = 3; i < SECRET_BITWIDTH - 1; i++) {
			 // verifying all other bit wires are binary (as this is typically a
			 // secret
			 // witness by the prover)
			 generator.addBinaryAssertion(secretBits[i]);
		 }
	 }
 
	 private void computeYCoordinates() {
 
		 // Easy to handle if baseX is constant, otherwise, let the prover input
		 // a witness and verify some properties
 
		 if (basePoint.x instanceof ConstantWire) {
 
			 BigInteger x = ((ConstantWire) basePoint.x).getConstant();
			 basePoint.y = generator.createConstantWire(computeYCoordinate(x));
		 } else {
			 basePoint.y = generator.createProverWitnessWire();
			 generator.specifyProverWitnessComputation(new Instruction() {
				 public void evaluate(CircuitEvaluator evaluator) {
					 BigInteger x = evaluator.getWireValue(basePoint.x);
					 evaluator.setWireValue(basePoint.y, computeYCoordinate(x));
				 }
			 });
			 assertValidPointOnEC(basePoint.x, basePoint.y);
		 }
 
		 if (hPoint.x instanceof ConstantWire) {
			 BigInteger x = ((ConstantWire) hPoint.x).getConstant();
			 hPoint.y = generator.createConstantWire(computeYCoordinate(x));
		 } else {
			 hPoint.y = generator.createProverWitnessWire();
			 generator.specifyProverWitnessComputation(new Instruction() {
				 public void evaluate(CircuitEvaluator evaluator) {
					 BigInteger x = evaluator.getWireValue(hPoint.x);
					 evaluator.setWireValue(hPoint.y, computeYCoordinate(x));
				 }
			 });
			 assertValidPointOnEC(hPoint.x, hPoint.y);
		 }
	 }
 
	 // this is only called, when Wire y is provided as witness by the prover
	 // (not as input to the gadget)
	 private void assertValidPointOnEC(Wire x, Wire y) {
		 Wire ySqr = y.mul(y);
		 Wire xSqr = x.mul(x);
		 Wire xCube = xSqr.mul(x);
		 generator.addEqualityAssertion(ySqr, xCube.add(xSqr.mul(COEFF_A))
				 .add(x));
	 }
 
	 private AffinePoint[] preprocess(AffinePoint p) {
		 AffinePoint[] precomputedTable = new AffinePoint[secretBits.length];
		 precomputedTable[0] = p;
		 for (int j = 1; j < secretBits.length; j += 1) {
			 precomputedTable[j] = doubleAffinePoint(precomputedTable[j - 1]);
		 }
		 return precomputedTable;
	 }
 
	 /**
	  * Performs scalar multiplication (secretBits must comply with the
	  * conditions above)
	  */
	 private AffinePoint mul(AffinePoint p, Wire[] secretBits,
			 AffinePoint[] precomputedTable) {
 
		 AffinePoint result = new AffinePoint(
				 precomputedTable[secretBits.length - 1]);
		 for (int j = secretBits.length - 2; j >= 0; j--) {
			 AffinePoint tmp = addAffinePoints(result, precomputedTable[j]);
			 Wire isOne = secretBits[j];
			 result.x = result.x.add(isOne.mul(tmp.x.sub(result.x)));
			 result.y = result.y.add(isOne.mul(tmp.y.sub(result.y)));
		 }
		 return result;
	 }
 
	 private AffinePoint doubleAffinePoint(AffinePoint p) {
		 Wire x_2 = p.x.mul(p.x);
		 Wire l1 = new FieldDivisionGadget(x_2.mul(3)
				 .add(p.x.mul(COEFF_A).mul(2)).add(1), p.y.mul(2))
				 .getOutputWires()[0];
		 Wire l2 = l1.mul(l1);
		 Wire newX = l2.sub(COEFF_A).sub(p.x).sub(p.x);
		 Wire newY = p.x.mul(3).add(COEFF_A).sub(l2).mul(l1).sub(p.y);
		 return new AffinePoint(newX, newY);
	 }
 
	 private AffinePoint addAffinePoints(AffinePoint p1, AffinePoint p2) {
		 Wire diffY = p1.y.sub(p2.y);
		 Wire diffX = p1.x.sub(p2.x);
		 Wire q = new FieldDivisionGadget(diffY, diffX).getOutputWires()[0];
		 Wire q2 = q.mul(q);
		 Wire q3 = q2.mul(q);
		 Wire newX = q2.sub(COEFF_A).sub(p1.x).sub(p2.x);
		 Wire newY = p1.x.mul(2).add(p2.x).add(COEFF_A).mul(q).sub(q3).sub(p1.y);
		 return new AffinePoint(newX, newY);
	 }
 
	 @Override
	 public Wire[] getOutputWires() {
		 return new Wire[] { outputPublicValue, sharedSecret };
	 }
 
	 /**
	  * Computes the Y coordinate of a point on the elliptic curve for a given X coordinate.
	  * Updated to use Bouncy Castle 1.79 methods for modular square root computation.
	  */
	 public static BigInteger computeYCoordinate(BigInteger x) {
		 BigInteger xSqred = x.multiply(x).mod(Config.FIELD_PRIME);
		 BigInteger xCubed = xSqred.multiply(x).mod(Config.FIELD_PRIME);
		 BigInteger ySqred = xCubed.add(COEFF_A.multiply(xSqred)).add(x)
				 .mod(Config.FIELD_PRIME);
		 
		 // Using Bouncy Castle 1.79's square root calculation method
		 return modSqrt(ySqred, Config.FIELD_PRIME);
	 }
	 
	 /**
	  * Computes modular square root using the Tonelli-Shanks algorithm.
	  * This replaces the IntegerFunctions.ressol method from legacy code.
	  */
	 private static BigInteger modSqrt(BigInteger a, BigInteger p) {
		 if (a.equals(BigInteger.ZERO)) {
			 return BigInteger.ZERO;
		 }
		 
		 if (!legendreSymbol(a, p).equals(BigInteger.ONE)) {
			 return null; // No solution exists
		 }
		 
		 if (p.mod(BigInteger.valueOf(4)).equals(BigInteger.valueOf(3))) {
			 // If p ≡ 3 (mod 4), then sqrt(a) = a^((p+1)/4) mod p
			 return a.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), p);
		 }
		 
		 // Implement Tonelli-Shanks algorithm for p ≡ 1 (mod 4)
		 BigInteger q = p.subtract(BigInteger.ONE);
		 int s = 0;
		 
		 while (q.and(BigInteger.ONE).equals(BigInteger.ZERO)) {
			 q = q.shiftRight(1);
			 s++;
		 }
		 
		 if (s == 1) {
			 return a.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), p);
		 }
		 
		 // Find a quadratic non-residue z
		 BigInteger z = BigInteger.valueOf(2);
		 while (legendreSymbol(z, p).equals(BigInteger.ONE)) {
			 z = z.add(BigInteger.ONE);
		 }
		 
		 BigInteger c = z.modPow(q, p);
		 BigInteger r = a.modPow(q.add(BigInteger.ONE).divide(BigInteger.valueOf(2)), p);
		 BigInteger t = a.modPow(q, p);
		 int m = s;
		 
		 while (!t.equals(BigInteger.ONE)) {
			 int i = 0;
			 BigInteger temp = t;
			 
			 while (!temp.equals(BigInteger.ONE)) {
				 temp = temp.multiply(temp).mod(p);
				 i++;
				 if (i == m) {
					 return null; // No solution exists
				 }
			 }
			 
			 BigInteger b = c.modPow(BigInteger.ONE.shiftLeft(m - i - 1), p);
			 r = r.multiply(b).mod(p);
			 c = b.multiply(b).mod(p);
			 t = t.multiply(c).mod(p);
			 m = i;
		 }
		 
		 return r;
	 }
	 
	 /**
	  * Calculates the Legendre symbol (a/p) which is:
	  * 1 if a is a quadratic residue modulo p
	  * -1 if a is a quadratic non-residue modulo p
	  * 0 if a ≡ 0 (mod p)
	  */
	 private static BigInteger legendreSymbol(BigInteger a, BigInteger p) {
		 if (a.mod(p).equals(BigInteger.ZERO)) {
			 return BigInteger.ZERO;
		 }
		 
		 return a.modPow(p.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2)), p);
	 }
 
	 public void validateInputs() {
		 generator.addOneAssertion(basePoint.x.checkNonZero());
		 assertValidPointOnEC(basePoint.x, basePoint.y);
		 assertPointOrder(basePoint, baseTable);
		 generator.addOneAssertion(hPoint.x.checkNonZero());
		 assertValidPointOnEC(hPoint.x, hPoint.y);
		 assertPointOrder(basePoint, baseTable);
		 assertPointOrder(hPoint, hTable);
	 }
 
	 private void assertPointOrder(AffinePoint p, AffinePoint[] table) {
 
		 Wire o = generator.createConstantWire(SUBGROUP_ORDER);
		 Wire[] bits = o.getBitWires(SUBGROUP_ORDER.bitLength()).asArray();
 
		 AffinePoint result = new AffinePoint(table[bits.length - 1]);
		 for (int j = bits.length - 2; j >= 1; j--) {
			 AffinePoint tmp = addAffinePoints(result, table[j]);
			 Wire isOne = bits[j];
			 result.x = result.x.add(isOne.mul(tmp.x.sub(result.x)));
			 result.y = result.y.add(isOne.mul(tmp.y.sub(result.y)));
		 }
 
		 // verify that: result = -p
		 generator.addEqualityAssertion(result.x, p.x);
		 generator.addEqualityAssertion(result.y, p.y.mul(-1));
 
		 // the reason the last iteration is handled separately is that the
		 // addition of
		 // affine points will throw an error due to not finding inverse for zero
		 // at the last iteration of the scalar multiplication. So, the check in
		 // the last iteration is done manually
 
		 // TODO: add more tests to check this method
 
	 }
 
	 public Wire getOutputPublicValue() {
		 return outputPublicValue;
	 }
 
	 public Wire getSharedSecret() {
		 return sharedSecret;
	 }
 }