package com.newhost.qwertylauncher.feature.herald

/** One of Sir Alex's poses: as a [posture] for his picture, and as a text [frame] without the ascii art module. */
internal class Move(val posture: Posture, val frame: Frame) {
    val id: String get() = posture.id
}

/** Every pose he strikes, by name. */
internal object Moves {
    val STAND = Move(Posture("stand"), Sprites.STAND)

    /** Standing about: shifting his weight, the plume stirring, looking around. */
    val IDLE1 = Move(Posture("idle1", frontLeg = 6f, backLeg = -10f, plume = 5f, frontArm = 22f), Sprites.STAND)
    val IDLE2 = Move(Posture("idle2", lean = -3f, frontLeg = 10f, backLeg = -6f, plume = -3f, visor = true), Sprites.STAND)
    val WALK1 = Move(Posture("walk1", frontLeg = 24f, backLeg = -22f, frontArm = 15f, backArm = 30f, plume = 3f), Sprites.WALK1)
    val WALK2 = Move(Posture("walk2", frontLeg = -4f, backLeg = 6f, frontArm = 32f, backArm = 12f, plume = -2f), Sprites.WALK2)
    val RUN1 = Move(
        Posture("run1", lean = 16f, frontLeg = 38f, backLeg = -34f, frontArm = 60f, frontElbow = 40f, blade = 70f, plume = 10f),
        Sprites.RUN1,
    )
    val RUN2 = Move(
        Posture("run2", lean = 16f, frontLeg = -18f, backLeg = 22f, frontArm = 40f, frontElbow = 50f, blade = 80f, plume = 14f),
        Sprites.RUN2,
    )
    val JUMP = Move(
        Posture("jump", lift = 14f, frontLeg = 30f, backLeg = -30f, frontArm = 150f, frontElbow = 10f, blade = 20f, backArm = 60f, plume = 12f),
        Sprites.JUMP,
    )
    val LAND = Move(Posture("land", lift = -12f, frontLeg = 34f, backLeg = -34f, frontArm = 55f, backArm = 40f, plume = -6f), Sprites.LAND)
    val FALL = Move(
        Posture(
            "fall", frontLeg = 30f, backLeg = -30f, frontArm = 160f, frontElbow = 0f, backArm = -160f, backElbow = 0f,
            hold = Hold.NOTHING, visor = true, mouth = true, plume = -16f,
        ),
        Sprites.FALL,
    )
    val SPLAT = Move(Posture("splat", lying = true, visor = true, dizzy = true, hold = Hold.NOTHING, frontArm = 90f, backArm = -90f), Sprites.SPLAT)
    val NAP = Move(Posture("nap", lying = true, visor = true, eyesShut = true, hold = Hold.NOTHING, frontArm = 20f, backArm = 20f), Sprites.NAP)
    val WAVE = Move(
        Posture("wave", hold = Hold.NOTHING, frontArm = 165f, frontElbow = -25f, visor = true, mouth = true),
        Sprites.WAVE,
    )
    val BOW = Move(Posture("bow", lean = 38f, frontArm = 70f, frontElbow = 60f, blade = 150f, backArm = -10f), Sprites.BOW)

    val TRUMPET1 = Move(
        Posture("trumpet1", hold = Hold.TRUMPET, frontArm = 100f, frontElbow = -30f, backArm = 10f, plume = 2f),
        Sprites.TRUMPET1,
    )
    val TRUMPET2 = Move(
        Posture("trumpet2", lean = -6f, hold = Hold.TRUMPET, frontArm = 105f, frontElbow = -30f, backArm = 10f, plume = 9f),
        Sprites.TRUMPET2,
    )

    val LISTEN = Move(Posture("listen", lean = 8f, visor = true, backArm = 150f, backElbow = 60f, hold = Hold.SWORD), Sprites.LISTEN)
    val DANCE1 = Move(
        Posture("dance1", lift = 4f, frontLeg = 55f, backLeg = -5f, frontArm = 150f, frontElbow = 20f, blade = 10f, backArm = 120f, visor = true, mouth = true, plume = 12f),
        Sprites.DANCE1,
    )
    val DANCE2 = Move(
        Posture("dance2", frontLeg = 5f, backLeg = -55f, frontArm = 100f, frontElbow = 40f, blade = 60f, backArm = 150f, visor = true, plume = -10f),
        Sprites.DANCE2,
    )

    val FIDDLE1 = Move(Posture("fiddle1", lean = 18f, hold = Hold.NOTHING, frontArm = 85f, frontElbow = 10f, backArm = 30f), Sprites.FIDDLE1)
    val FIDDLE2 = Move(Posture("fiddle2", lean = 22f, hold = Hold.NOTHING, frontArm = 100f, frontElbow = -20f, backArm = 35f), Sprites.FIDDLE2)

    val FLINCH = Move(
        Posture(
            "flinch", lean = -12f, frontLeg = 16f, backLeg = -16f, frontArm = 150f, frontElbow = 20f, blade = 0f,
            backArm = -140f, backElbow = 20f, visor = true, mouth = true, plume = -12f,
        ),
        Sprites.FLINCH,
    )
    val HUFF = Move(
        Posture("huff", lean = -4f, hold = Hold.NOTHING, frontArm = 60f, frontElbow = 110f, backArm = 60f, backElbow = 110f, visor = true),
        Sprites.HUFF,
    )
    val SHIELD = Move(Posture("shield", shieldUp = true, frontLeg = 14f, backLeg = -14f, visor = true), Sprites.SHIELD)
    val PUSH1 = Move(
        Posture("push1", lean = 26f, frontLeg = 30f, backLeg = -34f, hold = Hold.NOTHING, frontArm = 85f, frontElbow = 0f, backArm = 70f, backElbow = 10f),
        Sprites.PUSH1,
    )
    val PUSH2 = Move(
        Posture("push2", lean = 30f, frontLeg = 22f, backLeg = -40f, hold = Hold.NOTHING, frontArm = 95f, frontElbow = -10f, backArm = 80f, backElbow = 0f),
        Sprites.PUSH2,
    )
    val READ = Move(Posture("read", lean = 6f, hold = Hold.SCROLL, frontArm = 70f, frontElbow = 50f, visor = true), Sprites.READ)
    val GAZE = Move(Posture("gaze", lean = -14f, visor = true, frontArm = 30f, backArm = 150f, backElbow = 50f), Sprites.GAZE)

    val SWING1 = Move(Posture("swing1", frontArm = 165f, frontElbow = -10f, blade = -15f, plume = 4f), Sprites.SWING1)
    val SWING2 = Move(Posture("swing2", lean = 10f, frontLeg = 26f, backLeg = -26f, frontArm = 95f, frontElbow = 0f, blade = 90f, plume = 8f), Sprites.SWING2)
    val SWING3 = Move(Posture("swing3", lean = 18f, frontLeg = 26f, backLeg = -26f, frontArm = 40f, frontElbow = 20f, blade = 150f, plume = 10f), Sprites.SWING3)
    val EN_GARDE = Move(
        Posture("engarde", frontLeg = 28f, backLeg = -26f, frontArm = 80f, frontElbow = 30f, blade = 55f, backArm = 40f, backElbow = 70f),
        Sprites.EN_GARDE,
    )

    val DANGLE1 = Move(
        Posture(
            "dangle1", frontLeg = 18f, backLeg = -6f, frontArm = 160f, frontElbow = 10f, hold = Hold.NOTHING,
            backArm = -150f, backElbow = -20f, visor = true, mouth = true, plume = -8f,
        ),
        Sprites.DANGLE1,
    )
    val DANGLE2 = Move(
        Posture(
            "dangle2", frontLeg = -8f, backLeg = 16f, frontArm = 140f, frontElbow = 30f, hold = Hold.NOTHING,
            backArm = -170f, backElbow = 0f, visor = true, plume = 8f,
        ),
        Sprites.DANGLE2,
    )

    /** Up and down his ladder, hand over hand; and setting it up. */
    val CLIMB1 = Move(
        Posture("climb1", frontLeg = 34f, backLeg = -4f, hold = Hold.NOTHING, frontArm = 170f, frontElbow = 0f, backArm = 140f, backElbow = 20f, plume = 4f),
        Sprites.DANGLE1,
    )
    val CLIMB2 = Move(
        Posture("climb2", frontLeg = -4f, backLeg = 30f, hold = Hold.NOTHING, frontArm = 140f, frontElbow = 20f, backArm = 170f, backElbow = 0f, plume = -3f),
        Sprites.DANGLE2,
    )
    val SET_LADDER = Move(Posture("setladder", lean = 10f, hold = Hold.NOTHING, frontArm = 130f, frontElbow = 10f, backArm = 120f, backElbow = 10f), Sprites.WAVE)

    /** Deep in thought (the AI working). */
    val PONDER = Move(Posture("ponder", lean = -4f, visor = true, hold = Hold.NOTHING, frontArm = 60f, frontElbow = 120f, backArm = 30f), Sprites.HUFF)

    /** Watering his tree. */
    val WATER1 = Move(Posture("water1", lean = 8f, hold = Hold.CAN, frontArm = 80f, frontElbow = 10f), Sprites.FIDDLE1)
    val WATER2 = Move(Posture("water2", lean = 12f, hold = Hold.CAN, frontArm = 90f, frontElbow = 0f, plume = 3f), Sprites.FIDDLE2)

    // --- His day ---

    /** Stretching in the morning, arms up high. */
    val STRETCH1 = Move(
        Posture("stretch1", lean = -8f, hold = Hold.NOTHING, frontArm = 170f, frontElbow = 0f, backArm = 170f, backElbow = 0f, visor = true, mouth = true),
        Sprites.WAVE,
    )
    val STRETCH2 = Move(
        Posture("stretch2", lean = 10f, hold = Hold.NOTHING, frontArm = 150f, frontElbow = 20f, backArm = 160f, backElbow = 10f, visor = true, eyesShut = true),
        Sprites.WAVE,
    )

    /** Polishing his sword, held flat across him. */
    val POLISH1 = Move(Posture("polish1", lean = 10f, frontArm = 80f, frontElbow = 10f, blade = 90f, backArm = 70f, backElbow = 20f, visor = true), Sprites.FIDDLE1)
    val POLISH2 = Move(Posture("polish2", lean = 10f, frontArm = 80f, frontElbow = 10f, blade = 90f, backArm = 90f, backElbow = 0f, visor = true), Sprites.FIDDLE2)

    /** A turkey leg: up to his mouth, and chewing. */
    val EAT1 = Move(Posture("eat1", hold = Hold.TURKEY, frontArm = 130f, frontElbow = 60f, visor = true, mouth = true), Sprites.FIDDLE1)
    val EAT2 = Move(Posture("eat2", hold = Hold.TURKEY, frontArm = 110f, frontElbow = 50f, visor = true, eyesShut = true, plume = 3f), Sprites.FIDDLE2)

    /** Walking the walls at night, his lantern held out. */
    val PATROL1 = Move(WALK1.posture.copy(id = "patrol1", hold = Hold.LANTERN, frontArm = 70f, frontElbow = 20f), Sprites.WALK1)
    val PATROL2 = Move(WALK2.posture.copy(id = "patrol2", hold = Hold.LANTERN, frontArm = 75f, frontElbow = 15f), Sprites.WALK2)

    /** Creeping past on tiptoe. */
    val TIPTOE1 = Move(Posture("tiptoe1", lean = 14f, lift = 3f, frontLeg = 16f, backLeg = -12f, frontArm = 60f, frontElbow = 70f, backArm = 40f, plume = 2f), Sprites.WALK1)
    val TIPTOE2 = Move(Posture("tiptoe2", lean = 14f, lift = 4f, frontLeg = -2f, backLeg = 4f, frontArm = 55f, frontElbow = 75f, backArm = 45f, plume = -2f), Sprites.WALK2)

    // --- Needs ---

    val JUGGLE1 = Move(Posture("juggle1", hold = Hold.BALLS, frontArm = 120f, frontElbow = 30f, backArm = 90f, backElbow = 40f, visor = true, plume = 4f), Sprites.DANCE1)
    val JUGGLE2 = Move(Posture("juggle2", hold = Hold.BALLS, frontArm = 90f, frontElbow = 40f, backArm = 120f, backElbow = 30f, visor = true, plume = -4f), Sprites.DANCE2)
    val KICK = Move(Posture("kick", lean = -10f, frontLeg = 70f, backLeg = -6f, frontArm = 10f, backArm = 40f, plume = 6f), Sprites.JUMP)

    // --- Weather, battery and the like ---

    val SHIVER1 = Move(Posture("shiver1", lean = 4f, hold = Hold.NOTHING, frontArm = 60f, frontElbow = 110f, backArm = 60f, backElbow = 110f, visor = true, plume = 2f), Sprites.HUFF)
    val SHIVER2 = Move(Posture("shiver2", lean = -4f, hold = Hold.NOTHING, frontArm = 62f, frontElbow = 108f, backArm = 58f, backElbow = 112f, visor = true, plume = -2f), Sprites.HUFF)
    val SUNBATHE = Move(Posture("sunbathe", lying = true, visor = true, eyesShut = true, hold = Hold.NOTHING, frontArm = 170f, backArm = 170f), Sprites.NAP)
    val SIT = Move(Posture("sit", lift = -30f, frontLeg = 85f, backLeg = 75f, frontArm = 40f, frontElbow = 40f, backArm = 30f, blade = 60f, visor = true), Sprites.LAND)
    val CHEER = Move(
        Posture("cheer", lift = 12f, frontLeg = 20f, backLeg = -20f, frontArm = 170f, frontElbow = 0f, blade = 0f, backArm = 170f, backElbow = 0f, visor = true, mouth = true, plume = 12f),
        Sprites.JUMP,
    )
    val PEEK = Move(Posture("peek", lean = -18f, frontLeg = 4f, backLeg = -14f, frontArm = 20f, backArm = 90f, backElbow = 20f, visor = true), Sprites.GAZE)
    val LETTER = Move(Posture("letter", lean = 6f, hold = Hold.LETTER, frontArm = 75f, frontElbow = 50f, visor = true), Sprites.READ)
    val GASP = Move(Posture("gasp", lean = -10f, hold = Hold.LETTER, frontArm = 120f, frontElbow = 20f, backArm = -120f, visor = true, mouth = true, plume = -8f), Sprites.FLINCH)

    // --- Chores ---

    val SWEEP1 = Move(Posture("sweep1", lean = 16f, hold = Hold.BROOM, frontArm = 50f, frontElbow = 10f, blade = 20f, backArm = 60f), Sprites.PUSH1)
    val SWEEP2 = Move(Posture("sweep2", lean = 18f, hold = Hold.BROOM, frontArm = 30f, frontElbow = 10f, blade = -20f, backArm = 50f), Sprites.PUSH2)
    val CHOP1 = Move(Posture("chop1", lean = -6f, hold = Hold.AXE, frontArm = 170f, frontElbow = 0f, blade = 0f, frontLeg = 20f, backLeg = -20f), Sprites.SWING1)
    val CHOP2 = Move(Posture("chop2", lean = 24f, hold = Hold.AXE, frontArm = 80f, frontElbow = 0f, blade = 100f, frontLeg = 20f, backLeg = -20f), Sprites.SWING3)
    val HAMMER1 = Move(Posture("hammer1", lean = 10f, hold = Hold.HAMMER, frontArm = 140f, frontElbow = 10f, blade = 10f), Sprites.FIDDLE1)
    val HAMMER2 = Move(Posture("hammer2", lean = 14f, hold = Hold.HAMMER, frontArm = 90f, frontElbow = 0f, blade = 90f), Sprites.FIDDLE2)

    /** Every move, for drawing them ahead of time. */
    val all = listOf(
        STAND, IDLE1, IDLE2, WALK1, WALK2, RUN1, RUN2, JUMP, LAND, FALL, SPLAT, NAP, WAVE, BOW, TRUMPET1, TRUMPET2, LISTEN,
        DANCE1, DANCE2, FIDDLE1, FIDDLE2, FLINCH, HUFF, SHIELD, PUSH1, PUSH2, READ, GAZE, SWING1, SWING2, SWING3,
        EN_GARDE, DANGLE1, DANGLE2, WATER1, WATER2, CLIMB1, CLIMB2, SET_LADDER, PONDER,
        STRETCH1, STRETCH2, POLISH1, POLISH2, EAT1, EAT2, PATROL1, PATROL2, TIPTOE1, TIPTOE2, JUGGLE1, JUGGLE2, KICK,
        SHIVER1, SHIVER2, SUNBATHE, SIT, CHEER, PEEK, LETTER, GASP, SWEEP1, SWEEP2, CHOP1, CHOP2, HAMMER1, HAMMER2,
    )
}
