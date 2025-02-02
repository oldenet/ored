/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.support;

import java.util.HashMap;

/**
 * Class that provides data structures filled with HTML Entities and correspondent char
 * value
 *
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public final class HTMLEntities {

	/**
	 * a Map where the HTML Entity is the value and the correspondent char is the key
	 */
	public static final HashMap<Character, String> encodeMap;

	/**
	 * a Map where the HTML Entity is the key and the correspondent char is the value
	 */
	public static final HashMap<String, Character> decodeMap;

	private static final Object[][] charArray = { { Character.valueOf((char) 0), "#0" },
			{ Character.valueOf((char) 34), "quot" }, { Character.valueOf((char) 38), "amp" },
			{ Character.valueOf((char) 39), "#39" }, { Character.valueOf((char) 60), "lt" },
			{ Character.valueOf((char) 62), "gt" }, { Character.valueOf((char) 160), "nbsp" },
			{ Character.valueOf((char) 161), "iexcl" }, { Character.valueOf((char) 162), "cent" },
			{ Character.valueOf((char) 163), "pound" }, { Character.valueOf((char) 164), "curren" },
			{ Character.valueOf((char) 165), "yen" }, { Character.valueOf((char) 166), "brvbar" },
			{ Character.valueOf((char) 167), "sect" }, { Character.valueOf((char) 168), "uml" },
			{ Character.valueOf((char) 169), "copy" }, { Character.valueOf((char) 170), "ordf" },
			{ Character.valueOf((char) 171), "laquo" }, { Character.valueOf((char) 172), "not" },
			{ Character.valueOf((char) 173), "shy" }, { Character.valueOf((char) 174), "reg" },
			{ Character.valueOf((char) 175), "macr" }, { Character.valueOf((char) 176), "deg" },
			{ Character.valueOf((char) 177), "plusmn" }, { Character.valueOf((char) 178), "sup2" },
			{ Character.valueOf((char) 179), "sup3" }, { Character.valueOf((char) 180), "acute" },
			{ Character.valueOf((char) 181), "micro" }, { Character.valueOf((char) 182), "para" },
			{ Character.valueOf((char) 183), "middot" }, { Character.valueOf((char) 184), "cedil" },
			{ Character.valueOf((char) 185), "sup1" }, { Character.valueOf((char) 186), "ordm" },
			{ Character.valueOf((char) 187), "raquo" }, { Character.valueOf((char) 188), "frac14" },
			{ Character.valueOf((char) 189), "frac12" }, { Character.valueOf((char) 190), "frac34" },
			{ Character.valueOf((char) 191), "iquest" }, { Character.valueOf((char) 192), "Agrave" },
			{ Character.valueOf((char) 193), "Aacute" }, { Character.valueOf((char) 194), "Acirc" },
			{ Character.valueOf((char) 195), "Atilde" }, { Character.valueOf((char) 196), "Auml" },
			{ Character.valueOf((char) 197), "Aring" }, { Character.valueOf((char) 198), "AElig" },
			{ Character.valueOf((char) 199), "Ccedil" }, { Character.valueOf((char) 200), "Egrave" },
			{ Character.valueOf((char) 201), "Eacute" }, { Character.valueOf((char) 202), "Ecirc" },
			{ Character.valueOf((char) 203), "Euml" }, { Character.valueOf((char) 204), "Igrave" },
			{ Character.valueOf((char) 205), "Iacute" }, { Character.valueOf((char) 206), "Icirc" },
			{ Character.valueOf((char) 207), "Iuml" }, { Character.valueOf((char) 208), "ETH" },
			{ Character.valueOf((char) 209), "Ntilde" }, { Character.valueOf((char) 210), "Ograve" },
			{ Character.valueOf((char) 211), "Oacute" }, { Character.valueOf((char) 212), "Ocirc" },
			{ Character.valueOf((char) 213), "Otilde" }, { Character.valueOf((char) 214), "Ouml" },
			{ Character.valueOf((char) 215), "times" }, { Character.valueOf((char) 216), "Oslash" },
			{ Character.valueOf((char) 217), "Ugrave" }, { Character.valueOf((char) 218), "Uacute" },
			{ Character.valueOf((char) 219), "Ucirc" }, { Character.valueOf((char) 220), "Uuml" },
			{ Character.valueOf((char) 221), "Yacute" }, { Character.valueOf((char) 222), "THORN" },
			{ Character.valueOf((char) 223), "szlig" }, { Character.valueOf((char) 224), "agrave" },
			{ Character.valueOf((char) 225), "aacute" }, { Character.valueOf((char) 226), "acirc" },
			{ Character.valueOf((char) 227), "atilde" }, { Character.valueOf((char) 228), "auml" },
			{ Character.valueOf((char) 229), "aring" }, { Character.valueOf((char) 230), "aelig" },
			{ Character.valueOf((char) 231), "ccedil" }, { Character.valueOf((char) 232), "egrave" },
			{ Character.valueOf((char) 233), "eacute" }, { Character.valueOf((char) 234), "ecirc" },
			{ Character.valueOf((char) 235), "euml" }, { Character.valueOf((char) 236), "igrave" },
			{ Character.valueOf((char) 237), "iacute" }, { Character.valueOf((char) 238), "icirc" },
			{ Character.valueOf((char) 239), "iuml" }, { Character.valueOf((char) 240), "eth" },
			{ Character.valueOf((char) 241), "ntilde" }, { Character.valueOf((char) 242), "ograve" },
			{ Character.valueOf((char) 243), "oacute" }, { Character.valueOf((char) 244), "ocirc" },
			{ Character.valueOf((char) 245), "otilde" }, { Character.valueOf((char) 246), "ouml" },
			{ Character.valueOf((char) 247), "divide" }, { Character.valueOf((char) 248), "oslash" },
			{ Character.valueOf((char) 249), "ugrave" }, { Character.valueOf((char) 250), "uacute" },
			{ Character.valueOf((char) 251), "ucirc" }, { Character.valueOf((char) 252), "uuml" },
			{ Character.valueOf((char) 253), "yacute" }, { Character.valueOf((char) 254), "thorn" },
			{ Character.valueOf((char) 255), "yuml" }, { Character.valueOf((char) 260), "#260" },
			{ Character.valueOf((char) 261), "#261" }, { Character.valueOf((char) 262), "#262" },
			{ Character.valueOf((char) 263), "#263" }, { Character.valueOf((char) 280), "#280" },
			{ Character.valueOf((char) 281), "#281" }, { Character.valueOf((char) 321), "#321" },
			{ Character.valueOf((char) 322), "#322" }, { Character.valueOf((char) 323), "#323" },
			{ Character.valueOf((char) 324), "#324" }, { Character.valueOf((char) 338), "OElig" },
			{ Character.valueOf((char) 339), "oelig" }, { Character.valueOf((char) 346), "#346" },
			{ Character.valueOf((char) 347), "#347" }, { Character.valueOf((char) 352), "Scaron" },
			{ Character.valueOf((char) 353), "scaron" }, { Character.valueOf((char) 376), "Yuml" },
			{ Character.valueOf((char) 377), "#377" }, { Character.valueOf((char) 378), "#378" },
			{ Character.valueOf((char) 379), "#379" }, { Character.valueOf((char) 380), "#380" },
			{ Character.valueOf((char) 402), "fnof" }, { Character.valueOf((char) 710), "circ" },
			{ Character.valueOf((char) 732), "tilde" }, { Character.valueOf((char) 913), "Alpha" },
			{ Character.valueOf((char) 914), "Beta" }, { Character.valueOf((char) 915), "Gamma" },
			{ Character.valueOf((char) 916), "Delta" }, { Character.valueOf((char) 917), "Epsilon" },
			{ Character.valueOf((char) 918), "Zeta" }, { Character.valueOf((char) 919), "Eta" },
			{ Character.valueOf((char) 920), "Theta" }, { Character.valueOf((char) 921), "Iota" },
			{ Character.valueOf((char) 922), "Kappa" }, { Character.valueOf((char) 923), "Lambda" },
			{ Character.valueOf((char) 924), "Mu" }, { Character.valueOf((char) 925), "Nu" },
			{ Character.valueOf((char) 926), "Xi" }, { Character.valueOf((char) 927), "Omicron" },
			{ Character.valueOf((char) 928), "Pi" }, { Character.valueOf((char) 929), "Rho" },
			{ Character.valueOf((char) 931), "Sigma" }, { Character.valueOf((char) 932), "Tau" },
			{ Character.valueOf((char) 933), "Upsilon" }, { Character.valueOf((char) 934), "Phi" },
			{ Character.valueOf((char) 935), "Chi" }, { Character.valueOf((char) 936), "Psi" },
			{ Character.valueOf((char) 937), "Omega" }, { Character.valueOf((char) 945), "alpha" },
			{ Character.valueOf((char) 946), "beta" }, { Character.valueOf((char) 947), "gamma" },
			{ Character.valueOf((char) 948), "delta" }, { Character.valueOf((char) 949), "epsilon" },
			{ Character.valueOf((char) 950), "zeta" }, { Character.valueOf((char) 951), "eta" },
			{ Character.valueOf((char) 952), "theta" }, { Character.valueOf((char) 953), "iota" },
			{ Character.valueOf((char) 954), "kappa" }, { Character.valueOf((char) 955), "lambda" },
			{ Character.valueOf((char) 956), "mu" }, { Character.valueOf((char) 957), "nu" },
			{ Character.valueOf((char) 958), "xi" }, { Character.valueOf((char) 959), "omicron" },
			{ Character.valueOf((char) 960), "pi" }, { Character.valueOf((char) 961), "rho" },
			{ Character.valueOf((char) 962), "sigmaf" }, { Character.valueOf((char) 963), "sigma" },
			{ Character.valueOf((char) 964), "tau" }, { Character.valueOf((char) 965), "upsilon" },
			{ Character.valueOf((char) 966), "phi" }, { Character.valueOf((char) 967), "chi" },
			{ Character.valueOf((char) 968), "psi" }, { Character.valueOf((char) 969), "omega" },
			{ Character.valueOf((char) 977), "thetasym" }, { Character.valueOf((char) 978), "upsih" },
			{ Character.valueOf((char) 982), "piv" }, { Character.valueOf((char) 8194), "ensp" },
			{ Character.valueOf((char) 8195), "emsp" }, { Character.valueOf((char) 8201), "thinsp" },
			{ Character.valueOf((char) 8204), "zwnj" }, { Character.valueOf((char) 8205), "zwj" },
			{ Character.valueOf((char) 8206), "lrm" }, { Character.valueOf((char) 8207), "rlm" },
			{ Character.valueOf((char) 8211), "ndash" }, { Character.valueOf((char) 8212), "mdash" },
			{ Character.valueOf((char) 8216), "lsquo" }, { Character.valueOf((char) 8217), "rsquo" },
			{ Character.valueOf((char) 8218), "sbquo" }, { Character.valueOf((char) 8220), "ldquo" },
			{ Character.valueOf((char) 8221), "rdquo" }, { Character.valueOf((char) 8222), "bdquo" },
			{ Character.valueOf((char) 8224), "dagger" }, { Character.valueOf((char) 8225), "Dagger" },
			{ Character.valueOf((char) 8226), "bull" }, { Character.valueOf((char) 8230), "hellip" },
			{ Character.valueOf((char) 8240), "permil" }, { Character.valueOf((char) 8242), "prime" },
			{ Character.valueOf((char) 8243), "Prime" }, { Character.valueOf((char) 8249), "lsaquo" },
			{ Character.valueOf((char) 8250), "rsaquo" }, { Character.valueOf((char) 8254), "oline" },
			{ Character.valueOf((char) 8260), "frasl" }, { Character.valueOf((char) 8364), "euro" },
			{ Character.valueOf((char) 8465), "image" }, { Character.valueOf((char) 8472), "weierp" },
			{ Character.valueOf((char) 8476), "real" }, { Character.valueOf((char) 8482), "trade" },
			{ Character.valueOf((char) 8501), "alefsym" }, { Character.valueOf((char) 8592), "larr" },
			{ Character.valueOf((char) 8593), "uarr" }, { Character.valueOf((char) 8594), "rarr" },
			{ Character.valueOf((char) 8595), "darr" }, { Character.valueOf((char) 8596), "harr" },
			{ Character.valueOf((char) 8629), "crarr" }, { Character.valueOf((char) 8656), "lArr" },
			{ Character.valueOf((char) 8657), "uArr" }, { Character.valueOf((char) 8658), "rArr" },
			{ Character.valueOf((char) 8659), "dArr" }, { Character.valueOf((char) 8660), "hArr" },
			{ Character.valueOf((char) 8704), "forall" }, { Character.valueOf((char) 8706), "part" },
			{ Character.valueOf((char) 8707), "exist" }, { Character.valueOf((char) 8709), "empty" },
			{ Character.valueOf((char) 8711), "nabla" }, { Character.valueOf((char) 8712), "isin" },
			{ Character.valueOf((char) 8713), "notin" }, { Character.valueOf((char) 8715), "ni" },
			{ Character.valueOf((char) 8719), "prod" }, { Character.valueOf((char) 8721), "sum" },
			{ Character.valueOf((char) 8722), "minus" }, { Character.valueOf((char) 8727), "lowast" },
			{ Character.valueOf((char) 8730), "radic" }, { Character.valueOf((char) 8733), "prop" },
			{ Character.valueOf((char) 8734), "infin" }, { Character.valueOf((char) 8736), "ang" },
			{ Character.valueOf((char) 8743), "and" }, { Character.valueOf((char) 8744), "or" },
			{ Character.valueOf((char) 8745), "cap" }, { Character.valueOf((char) 8746), "cup" },
			{ Character.valueOf((char) 8747), "int" }, { Character.valueOf((char) 8756), "there4" },
			{ Character.valueOf((char) 8764), "sim" }, { Character.valueOf((char) 8773), "cong" },
			{ Character.valueOf((char) 8776), "asymp" }, { Character.valueOf((char) 8800), "ne" },
			{ Character.valueOf((char) 8801), "equiv" }, { Character.valueOf((char) 8804), "le" },
			{ Character.valueOf((char) 8805), "ge" }, { Character.valueOf((char) 8834), "sub" },
			{ Character.valueOf((char) 8835), "sup" }, { Character.valueOf((char) 8836), "nsub" },
			{ Character.valueOf((char) 8838), "sube" }, { Character.valueOf((char) 8839), "supe" },
			{ Character.valueOf((char) 8853), "oplus" }, { Character.valueOf((char) 8855), "otimes" },
			{ Character.valueOf((char) 8869), "perp" }, { Character.valueOf((char) 8901), "sdot" },
			{ Character.valueOf((char) 8968), "lceil" }, { Character.valueOf((char) 8969), "rceil" },
			{ Character.valueOf((char) 8970), "lfloor" }, { Character.valueOf((char) 8971), "rfloor" },
			{ Character.valueOf((char) 9001), "lang" }, { Character.valueOf((char) 9002), "rang" },
			{ Character.valueOf((char) 9674), "loz" }, { Character.valueOf((char) 9824), "spades" },
			{ Character.valueOf((char) 9827), "clubs" }, { Character.valueOf((char) 9829), "hearts" },
			{ Character.valueOf((char) 9830), "diams" } };

	static {
		encodeMap = new HashMap<Character, String>();
		decodeMap = new HashMap<String, Character>();

		for (Object[] ch : charArray) {
			encodeMap.put((Character) ch[0], (String) ch[1]);
			decodeMap.put((String) ch[1], (Character) ch[0]);
		}

	}

}
