/*
 * Copyright (C) 2020 Roumen Petrov.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.termoneplus.compat;

/*
 * Local fork (dn-shell): the upstream class branches on Build.VERSION_CODES.N
 * (API 24) and uses android.icu.lang.UCharacter there. This build compiles
 * against an old android.jar, so keep only the API-1 path -- Character for
 * surrogate handling and android.text.AndroidCharacter for the (BMP) east
 * asian width. Semantics for BMP text are unchanged.
 */
public class CharacterCompat {
    public static int charCount(int cp /*code point*/) {
        return Character.charCount(cp);
    }

    public static int toChars(int cp, char[] dst, int dstIndex) {
        return Character.toChars(cp, dst, dstIndex);
    }

    @SuppressWarnings("deprecation")
    public static boolean isEastAsianDoubleWidth(int ch /*code point*/) {
        // Android's getEastAsianWidth() only works for BMP characters
        switch (android.text.AndroidCharacter.getEastAsianWidth((char) ch)) {
            case android.text.AndroidCharacter.EAST_ASIAN_WIDTH_FULL_WIDTH:
            case android.text.AndroidCharacter.EAST_ASIAN_WIDTH_WIDE:
                return true;
        }
        return false;
    }
}
