package com.newhost.qwertylauncher.feature.herald

/**
 * One picture of Sir Alex: [art] lines, and [paint], a colour key for each
 * character (see [Sprites.PALETTE]).
 */
internal class Frame(val art: List<String>, val paint: List<String>) {
    val height get() = art.size
}

/**
 * Sir Alex of Quertia, hedge knight and herald, in plain ASCII, facing
 * right: a red plume on a bucket helm, a green tabard over mail, a wooden
 * shield with a gold boss on his left arm, and his sword in his right.
 * ```
 *     ,~~
 *    [==]
 *   _|##|_
 *  (@)##|\
 *    |==| \
 *   _/  \_
 * ```
 * Every frame is [WIDTH] columns wide, his feet on its last line. Colours
 * come from what each character is (the plume, the helm, the tabard...),
 * with some set by hand, like the blade; [mirror] turns him to face left.
 */
internal object Sprites {
    const val WIDTH = 10

    val PALETTE = mapOf(
        'r' to 0xFFFF5555.toInt(), // plume, pennant
        's' to 0xFFB8C4CE.toInt(), // steel: helm, mail, arms
        'g' to 0xFF4FC34F.toInt(), // the green tabard of a hedge knight
        'y' to 0xFFFFD23F.toInt(), // gold: shield boss, trumpet
        'b' to 0xFFB9824F.toInt(), // leather and wood: belt, boots, shield
        'w' to 0xFFF2F6FA.toInt(), // the blade, scrolls
        'd' to 0xFF9AA5B1.toInt(), // snores
        'k' to 0xFF6B7885.toInt(), // shadowed steel: the visor
    )

    val STAND = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|\\  ",
        "   |==| \\ ",
        "  _/  \\_  ",
        paint = mapOf(4 to "        w "),
    )
    val WALK1 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|\\  ",
        "   |==| \\ ",
        " _/    \\_ ",
        paint = mapOf(4 to "        w "),
    )
    val WALK2 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|\\  ",
        "   |==|  \\",
        "   _||_   ",
        paint = mapOf(4 to "         w"),
    )
    val RUN1 = frame(
        "   ~~~,   ",
        "     [==] ",
        "    _|##|_",
        "  (@)##|--",
        "    /==|  ",
        "  _/    \\_",
    )
    val RUN2 = frame(
        "  ~~~,    ",
        "    [==]  ",
        "   _|##|_ ",
        " (@)##|-- ",
        "    |==\\  ",
        "     _/\\_ ",
    )
    val JUMP = frame(
        "    ,~~  /",
        "   [==] / ",
        " (@)##|/  ",
        "   |##|   ",
        "   |==|   ",
        "   /  \\   ",
        paint = mapOf(0 to "         w", 1 to "        w "),
    )
    val LAND = frame(
        "    ,~~   ",
        "   [==]   ",
        " (@)##|\\  ",
        "  _/==\\_\\ ",
        paint = mapOf(3 to "        w "),
    )
    val FALL = frame(
        "\\   ,~~  /",
        " \\ [OO] / ",
        "   |##|   ",
        "   |##|   ",
        "   |==|   ",
        "   ^  ^   ",
    )
    val SPLAT = frame("~[==]##=_/")
    val NAP = frame("~[==]##=\\_")
    val WAVE = frame(
        "    ,~~ \\ ",
        "   [==]  \\",
        "  _|##|_/ ",
        " (@)##|   ",
        "   |==|   ",
        "  _/  \\_  ",
    )
    val BOW = frame(
        "          ",
        "    ,~~   ",
        "  _[==]_  ",
        " (@)##|\\  ",
        "   |==| \\ ",
        "  _/  \\_  ",
        paint = mapOf(4 to "        w "),
    )

    /** The herald's trumpet, to his helm. */
    val TRUMPET1 = frame(
        "    ,~~   ",
        "   [==]=<<",
        "  _|##|/  ",
        " (@)##|   ",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(1 to "       yyy"),
    )
    val TRUMPET2 = frame(
        "    ,~~   ",
        "   [==]=<{",
        "  _|##|/  ",
        " (@)##|   ",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(1 to "       yyy"),
    )

    /** A hand cupped to his ear, then dancing. */
    val LISTEN = frame(
        "    ,~~   ",
        "   [==])  ",
        "  _|##|/  ",
        " (@)##|   ",
        "   |==|   ",
        "  _/  \\_  ",
    )
    val DANCE1 = frame(
        "  \\ ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|\\  ",
        "   |==|  \\",
        "   \\  /\\_ ",
        paint = mapOf(4 to "         w"),
    )
    val DANCE2 = frame(
        "    ,~~ / ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|\\  ",
        "   |==| \\ ",
        " _/\\  /   ",
        paint = mapOf(4 to "        w "),
    )

    /** Reaching into a tile, up to no good. */
    val FIDDLE1 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|___",
        " (@)##|  ,",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(2 to "       sss", 3 to "         s"),
    )
    val FIDDLE2 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|__/",
        " (@)##|   ",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(2 to "       ss "),
    )

    /** Caught. */
    val FLINCH = frame(
        "  ! ,~~ ! ",
        "   [OO]   ",
        "  \\|##|/  ",
        " (@)##|   ",
        "   |==|   ",
        "  _/  \\_  ",
    )
    val HUFF = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)=##=  ",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(3 to "    s  s  "),
    )

    val SHIELD = frame(
        "  (==@==) ",
        "   \\,~~/  ",
        "   [==]   ",
        "  _|##|_  ",
        "   |##|   ",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(0 to "  bbbybbb "),
    )
    val PUSH1 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|___",
        " (@)##|  ,",
        "   |==|   ",
        " _/    \\_ ",
        paint = mapOf(2 to "       sss", 3 to "         s"),
    )
    val PUSH2 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|__/",
        " (@)##|   ",
        "   |==|   ",
        "   _/\\_   ",
        paint = mapOf(2 to "       ss "),
    )
    val READ = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|[~]",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(3 to "       www"),
    )
    val GAZE = frame(
        "    ,~~ * ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|\\  ",
        "   |==| \\ ",
        "  _/  \\_  ",
        paint = mapOf(4 to "        w "),
    )

    /** Sword drill: up, forward, down. */
    val SWING1 = frame(
        "    ,~~  |",
        "   [==]  |",
        "  _|##|_/ ",
        " (@)##|   ",
        "   |==|   ",
        "  _/  \\_  ",
        paint = mapOf(0 to "         w", 1 to "         w"),
    )
    val SWING2 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|-==",
        "   |==|   ",
        " _/    \\_ ",
        paint = mapOf(3 to "       sww"),
    )
    val SWING3 = frame(
        "    ,~~   ",
        "   [==]   ",
        "  _|##|_  ",
        " (@)##|\\  ",
        "   |==| \\ ",
        "  _/  \\_\\ ",
        paint = mapOf(4 to "        w ", 5 to "        w "),
    )
    val EN_GARDE = frame(
        "    ,~~  |",
        "   [==]  |",
        "  _|##|_/ ",
        " (@)##|   ",
        "   |==|   ",
        " _/    \\_ ",
        paint = mapOf(0 to "         w", 1 to "         w"),
    )

    /** Held up by the scruff, kicking. */
    val DANGLE1 = frame(
        "    ,~~   ",
        "   [OO]   ",
        "  /|##|\\  ",
        " (@)##| \\ ",
        "   |==|   ",
        "   /  \\   ",
        "  ^    ^  ",
        paint = mapOf(3 to "        w "),
    )
    val DANGLE2 = frame(
        "    ,~~   ",
        "   [OO]   ",
        "  \\|##|/  ",
        " (@)##|/  ",
        "   |==|   ",
        "    ||    ",
        "    ^^    ",
        paint = mapOf(3 to "       w  "),
    )

    /** How tall his tallest frame is. */
    val TALL = listOf(STAND, SHIELD, DANGLE1, FALL).maxOf { it.height }

    private val FLIPPED = mapOf(
        '/' to '\\', '\\' to '/', '(' to ')', ')' to '(', '<' to '>', '>' to '<',
        '[' to ']', ']' to '[', '{' to '}', '}' to '{',
    )

    /** [frame] facing the other way. */
    fun mirror(frame: Frame) = Frame(
        frame.art.map { line -> line.reversed().map { FLIPPED[it] ?: it }.joinToString("") },
        frame.paint.map { it.reversed() },
    )

    /**
     * A frame from its lines (padded to [WIDTH]), coloured by what each
     * character is, unless [paint] sets a line's colours by hand (a space
     * there keeps the usual colour). The helm's line and those above it are
     * his head, where `=` is the visor rather than his belt.
     */
    private fun frame(vararg lines: String, paint: Map<Int, String> = emptyMap()): Frame {
        val art = lines.map { it.padEnd(WIDTH) }
        val helm = art.indexOfFirst { '[' in it }
        val keys = art.mapIndexed { row, line ->
            val hand = paint[row].orEmpty()
            line.mapIndexed { col, char ->
                hand.getOrNull(col)?.takeIf { it != ' ' } ?: key(char, head = row <= helm)
            }.joinToString("")
        }
        return Frame(art, keys)
    }

    private fun key(char: Char, head: Boolean): Char = when (char) {
        '~', ',' -> 'r'
        '#' -> 'g'
        '@', '!', '*' -> 'y'
        '(', ')', '_' -> 'b'
        '=' -> if (head) 'k' else 'b'
        '[', ']', 'O' -> 's'
        'z', 'Z' -> 'd'
        else -> 's'
    }
}
