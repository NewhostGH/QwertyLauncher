package com.newhost.qwertylauncher.feature.herald

/**
 * What Sir Alex says, besides his scenes' own lines: herald's olde speech,
 * seasoned with the sayings of other realms (Westeros, Azeroth, Tamriel...).
 */
internal object Lines {
    val greetings = listOf(
        "Good morrow, my liege!",
        "Well met, traveller!",
        "Stay awhile and listen!",
        "Winter is coming. So am I!",
        "'Tis I! Thy herald returns!",
        "Hark! I bring news! ...I forgot the news.",
        "Did thou miss me? Say yes.",
        "Ready to work!",
        "I have returned from a most perilous quest. The kitchen.",
    )

    val farewells = listOf(
        "Duty calls! Farewell!",
        "For the Alliance! ...and the Horde. A herald serves all.",
        "Valar dohaeris. All men must serve. Even me.",
        "Job's done!",
        "It is dangerous to go alone. So I take myself.",
        "Off to polish mine armour.",
        "I shall return! Probably when thou least expect it.",
        "My watch is ended. For now.",
    )

    val taunts = listOf(
        "Unhand me, knave!",
        "Thou pokest like a peasant!",
        "Thou knowest nothing, my liege.",
        "Thou art not prepared!",
        "Leeroy Jenk- ...forgive me, a reflex.",
        "What is poked may never die!",
        "A Quertian always pays his debts. Remember that.",
        "I used to be an adventurer like thee. Then I took a finger to the knee.",
        "Zug zug. That means stop, in orcish.",
        "Me not that kind of orc!",
        "Mine armour was freshly polished!",
        "I am a knight! I do not tickle! ...Much.",
        "Stop poking me! Stop poking me!",
        "The king shall hear of this!",
    )

    val huffs = listOf(
        "Fine! I shall find a kinder screen!",
        "Valar morghulis! All men must go. Starting with me.",
        "Hmph! I know when I am not wanted!",
        "That is IT. I am telling the king in the north.",
    )

    val carried = listOf(
        "Unhand me, knave!",
        "Put me down! I fear heights!",
        "Hodor! HODOR!",
        "I am not a sack of turnips!",
        "This is most undignified!",
        "Fly, you fool! ...not me! Put me DOWN!",
        "Where art thou taking me?!",
    )

    val putDown = listOf(
        "Thank thee. I think.",
        "I meant to land there.",
        "A knight always lands on his feet. Eventually.",
        "Next time, ask first.",
        "The ground and I are old friends.",
    )

    val afterDrill = listOf(
        "The bugs tremble before me.",
        "Fus ro dah! ...It works better in Skyrim.",
        "Not one bug survived. I checked. Twice.",
        "The sharp end goes in the bug. Lesson one.",
        "Mine form is flawless. Do not look too closely.",
    )

    val afterFall = listOf(
        "I meant to do that.",
        "A tactical descent!",
        "Who put that title there?",
        "Nobody saw that. Right?",
        "The ground rose up to meet me. Rude.",
    )

    val wanderings = listOf(
        "A fine view from here.",
        "I claim this tile in the name of Quertia!",
        "Something need doing?",
        "Work work.",
        "Hmm. Suspicious.",
        "All quiet on this front.",
        "The night is dark and full of apps.",
    )

    val compliments = listOf(
        "Thy home screen is the finest in the Seven Kingdoms.",
        "Such a noble keyboard thou hast.",
        "Thou typest with great valour.",
        "Of all the phones I have guarded, this is my favourite.",
    )

    val realm = listOf(
        "My realm! Small, but mine.",
        "One day, a keep. With a moat. And a gift shop.",
        "The North remembers. So does this tree.",
        "Every lord started with a tree. Probably.",
    )

    /** Answers when he has no wits to think with (no AI set up). */
    val noWits = listOf(
        "Thy words are wise, my liege. I understood none of them.",
        "Aye! ...What?",
        "I shall ponder this. At length. Later.",
        "Set up the AI, my liege, and I shall have wits for thee.",
        "A herald hears all. A herald understands... some.",
    )

    /** Heralds' asides, proclaimed now and then. */
    val quips = listOf(
        "Winter is coming. Charge thy phone.",
        "A herald has no name. Wait, yes he does. Sir Alex.",
        "Chaos is a ladder. Tidiness is a tidy home screen.",
        "When thou playest the game of apps, thou closest them or thy battery dies.",
        "You are not prepared! ...for how many tabs thou hast open.",
        "Lok'tar ogar! That means good day, I am told.",
        "It is dangerous to go alone. Take this: a tip. Type ? to ask the AI.",
        "What do we say to the god of low battery? Not today.",
        "Hold the door! ...Or the enter key, for more options.",
        "I have seen things thou people would not believe. Mostly notifications.",
    )

    // --- His day ---

    val mornings = listOf(
        "Morning, my liege. The realm awakes.",
        "A new day! Another chance for glory. Or a nap.",
        "Up with the lark. The lark was not pleased.",
    )

    val fed = listOf(
        "A knight marches on his stomach.",
        "Delicious. Compliments to... me.",
        "Now THAT is a turkey leg worthy of a feast.",
    )

    val patrol = listOf(
        "All quiet on the eastern tile.",
        "Night gathers, and now my watch begins.",
        "Nothing stirs. Good. Probably good.",
    )

    // --- Weather ---

    val rain = listOf(
        "'Tis raining cats and direwolves!",
        "Rain, rain, go away. Come again some feast day.",
        "Mine shield makes a fine umbrella.",
    )

    val cold = listOf(
        "B-b-brr. Winter is here.",
        "Mine armour is an icebox.",
        "Who stole the sun?",
    )

    val sunny = listOf(
        "Ahh. The sun of Dorne.",
        "A knight must rest his armour now and then.",
        "Wake me when there is a dragon.",
    )

    // --- The phone ---

    val musicStopped = listOf(
        "...I was stretching.",
        "Thou saw nothing. Nothing!",
        "Knights do not dance. That was a battle stance.",
    )

    val letters = listOf(
        "Gasp! Tidings!",
        "By the old gods! News!",
        "A raven! I must tell someone. Anyone!",
    )

    // --- Moods and memories ---

    val peeks = listOf(
        "...is it safe?",
        "No poking today, I hope.",
        "I have my eye on thee.",
    )

    val welcomeBack = listOf(
        "Welcome back, my liege!",
        "Thou returnest! The realm rejoices.",
        "My liege! I kept thy home screen warm.",
    )

    val awayLong = listOf(
        "Thou wert away long... I feared the worst.",
        "My liege! I thought thou hadst gone to the Wall.",
        "Days! It has been days! I counted every one.",
    )

    val nervous = listOf(
        "*eyes the edge nervously*",
        "I remember this tile. I remember the fall.",
        "Not too close to the edge, not this time.",
    )

    // --- Knocks at night ---

    val fiveMore = listOf(
        "...five more minutes...",
        "Zzz... go away... 'tis night...",
        "The herald is asleep. Come back at dawn.",
    )

    val wokenUp = listOf(
        "FINE. I am up. What is it?",
        "Who knocks at this hour?!",
        "This had better be a dragon.",
    )
}
