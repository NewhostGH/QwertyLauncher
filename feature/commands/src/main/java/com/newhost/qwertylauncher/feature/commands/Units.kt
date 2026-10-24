package com.newhost.qwertylauncher.feature.commands

/**
 * A unit: [factor] times it (plus [offset], for temperatures) is the base
 * unit of its [kind]. [name] is how answers show it.
 */
internal class Measure(val name: String, val kind: String, val factor: Double, val offset: Double = 0.0) {
    fun toBase(x: Double) = x * factor + offset
    fun fromBase(x: Double) = (x - offset) / factor
}

/** The units conversions know, by every name they can be typed as. */
internal object Units {
    private val table = mutableMapOf<String, Measure>()

    private fun add(kind: String, factor: Double, vararg names: String, offset: Double = 0.0) {
        val unit = Measure(names.first(), kind, factor, offset)
        names.forEach { table[it] = unit }
    }

    init {
        // Length, in metres.
        add("length", 1e-3, "mm", "millimetre", "millimetres", "millimeter", "millimeters")
        add("length", 1e-2, "cm", "centimetre", "centimetres", "centimeter", "centimeters")
        add("length", 1.0, "m", "metre", "metres", "meter", "meters")
        add("length", 1e3, "km", "kilometre", "kilometres", "kilometer", "kilometers")
        add("length", 0.0254, "in", "inch", "inches")
        add("length", 0.3048, "ft", "foot", "feet")
        add("length", 0.9144, "yd", "yard", "yards")
        add("length", 1609.344, "mi", "mile", "miles")
        add("length", 1852.0, "nmi", "nautical mile", "nautical miles")
        // Mass, in kilograms.
        add("mass", 1e-6, "mg", "milligram", "milligrams")
        add("mass", 1e-3, "g", "gram", "grams")
        add("mass", 1.0, "kg", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 1e3, "t", "tonne", "tonnes", "ton", "tons")
        add("mass", 0.028349523125, "oz", "ounce", "ounces")
        add("mass", 0.45359237, "lb", "lbs", "pound", "pounds")
        add("mass", 6.35029318, "st", "stone", "stones")
        // Volume, in litres (US cups, pints and gallons).
        add("volume", 1e-3, "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        add("volume", 1e-2, "cl")
        add("volume", 0.1, "dl")
        add("volume", 1.0, "l", "litre", "litres", "liter", "liters")
        add("volume", 0.00492892159375, "tsp", "teaspoon", "teaspoons")
        add("volume", 0.01478676478125, "tbsp", "tablespoon", "tablespoons")
        add("volume", 0.2365882365, "cup", "cups")
        add("volume", 0.0295735295625, "floz", "fl oz")
        add("volume", 0.473176473, "pt", "pint", "pints")
        add("volume", 0.946352946, "qt", "quart", "quarts")
        add("volume", 3.785411784, "gal", "gallon", "gallons")
        // Time, in seconds.
        add("time", 1e-3, "ms", "millisecond", "milliseconds")
        add("time", 1.0, "s", "sec", "secs", "second", "seconds")
        add("time", 60.0, "min", "mins", "minute", "minutes")
        add("time", 3600.0, "h", "hr", "hrs", "hour", "hours")
        add("time", 86400.0, "d", "day", "days")
        add("time", 604800.0, "wk", "week", "weeks")
        add("time", 31557600.0, "yr", "year", "years")
        // Speed, in metres a second.
        add("speed", 1.0, "m/s")
        add("speed", 1 / 3.6, "km/h", "kmh", "kph")
        add("speed", 0.44704, "mph")
        add("speed", 1852 / 3600.0, "kn", "knot", "knots")
        // Area, in square metres.
        add("area", 1e-4, "cm2")
        add("area", 1.0, "m2")
        add("area", 1e6, "km2")
        add("area", 1e4, "ha", "hectare", "hectares")
        add("area", 4046.8564224, "acre", "acres")
        add("area", 0.09290304, "ft2")
        // Data, in bytes.
        add("data", 0.125, "bit", "bits")
        add("data", 1.0, "byte", "bytes")
        add("data", 1e3, "kb")
        add("data", 1e6, "mb")
        add("data", 1e9, "gb")
        add("data", 1e12, "tb")
        add("data", 1024.0, "kib")
        add("data", 1024.0 * 1024, "mib")
        add("data", 1024.0 * 1024 * 1024, "gib")
        add("data", 1024.0 * 1024 * 1024 * 1024, "tib")
        // Energy, in joules.
        add("energy", 1.0, "j", "joule", "joules")
        add("energy", 1e3, "kj")
        add("energy", 4.184, "cal")
        add("energy", 4184.0, "kcal")
        add("energy", 3600.0, "wh")
        add("energy", 3.6e6, "kwh")
        // Pressure, in pascals.
        add("pressure", 1.0, "pa")
        add("pressure", 100.0, "hpa", "mbar")
        add("pressure", 1e3, "kpa")
        add("pressure", 1e5, "bar")
        add("pressure", 101325.0, "atm")
        add("pressure", 6894.757293168, "psi")
        add("pressure", 133.322387415, "mmhg")
        // Temperature, in kelvin.
        add("temperature", 1.0, "c", "°c", "celsius", offset = 273.15)
        add("temperature", 5 / 9.0, "f", "°f", "fahrenheit", offset = 273.15 - 32 * 5 / 9.0)
        add("temperature", 1.0, "k", "kelvin")
        // Angles, in radians.
        add("angle", Math.PI / 180, "deg", "degree", "degrees")
        add("angle", 1.0, "rad", "radian", "radians")
    }

    /** Every name, longest first, so `5 nmi` is nautical miles and not `5 n` miles. */
    val names: List<Pair<String, Measure>> = table.entries.sortedByDescending { it.key.length }.map { it.key to it.value }

    fun find(name: String): Measure? = table[name.lowercase()]
}
