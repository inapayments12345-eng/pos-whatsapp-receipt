package com.ina.pos.receipt.util;

public class CardBrandUtil {

    public static String resolveBrand(String pan) {
        if (pan == null || pan.length() < 6) return "UNKNOWN";

        String bin = pan.replaceAll("\\s", "");
        if (bin.length() < 6) return "UNKNOWN";

        int firstOne  = Integer.parseInt(bin.substring(0, 1));
        int firstTwo  = Integer.parseInt(bin.substring(0, 2));
        int firstThree= Integer.parseInt(bin.substring(0, 3));
        int firstFour = Integer.parseInt(bin.substring(0, 4));
        int firstSix  = Integer.parseInt(bin.substring(0, 6));


        if (firstOne == 4) return "VISA";


        if ((firstTwo >= 51 && firstTwo <= 55) ||
            (firstFour >= 2221 && firstFour <= 2720)) {
            return "MASTERCARD";
        }


        if (firstTwo == 34 || firstTwo == 37) return "AMEX";

        if (firstFour == 6011 ||
            firstTwo == 65 ||
            (firstThree >= 644 && firstThree <= 649)) {
            return "DISCOVER";
        }


        if ((firstThree >= 300 && firstThree <= 305) ||
            firstTwo == 36 || firstTwo == 38) {
            return "DINERS";
        }


        if (firstFour >= 3528 && firstFour <= 3589) {
            return "JCB";
        }


        if (firstTwo == 50 || (firstTwo >= 56 && firstTwo <= 69)) {
            return "MAESTRO";
        }


        if (firstTwo == 60 || firstTwo == 65 || firstTwo == 81 || firstTwo == 82 ||
            (firstSix >= 508500 && firstSix <= 508999)) {
            return "RUPAY";
        }


        if (firstTwo == 62) return "UNIONPAY";


        return "UNKNOWN";
    }
}

