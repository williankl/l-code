package dev.lemonadecode.sessions.impl

import kotlin.random.Random

/** Playful branch names for new worktrees, like `worktree/generating-bugs`: easy to keep, easy to type over. */
internal object WorktreeNames {
  fun suggest(random: Random): String = "worktree/${Doing.random(random)}-${Things.random(random)}"

  private val Doing = listOf(
    "generating", "chasing", "herding", "taming", "juggling", "summoning", "untangling", "feeding",
    "polishing", "wrangling", "squashing", "hatching", "poking", "shaving", "bribing", "petting",
    "tickling", "rewiring", "befriending", "outsmarting", "defrosting", "hunting", "dodging",
    "caffeinating", "rebasing", "whispering", "baking", "negotiating", "squeezing", "spinning",
  )

  private val Things = listOf(
    "bugs", "semicolons", "pointers", "gremlins", "yaks", "ducks", "goblins", "segfaults",
    "stacktraces", "unicorns", "potatoes", "dragons", "lemons", "pixels", "regexes", "tabs",
    "commits", "gnomes", "penguins", "cookies", "threads", "monads", "packets", "carrots",
    "mutexes", "callbacks", "spaghetti", "hamsters", "linters", "zombies",
  )
}
