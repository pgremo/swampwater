package swampwater

fun String.beginWithUpperCase() = when (length) {
    0 -> ""
    1 -> toUpperCase()
    else -> this[0].toUpperCase() + this.substring(1)
}

fun String.toCamelCase() = split('_').joinToString("") { it.beginWithUpperCase() }.decapitalize()


