package com.ikko.aura.reco

/**
 * The queue of an artist's radio: their own songs lead, and what is close to them comes in between, one for every [OWN_PER_OTHER] of theirs.
 * A radio of Chappell Roan is Chappell Roan, with a little of what resembles her; not the other way round.
 *
 * Pure, so it can be checked without a device.
 */
object RadioMix {
    const val OWN_PER_OTHER = 3

    fun <T> mix(own: List<T>, others: List<T>, ownPerOther: Int = OWN_PER_OTHER): List<T> {
        val out = ArrayList<T>(own.size + others.size)
        val o = own.iterator()
        val x = others.iterator()
        while (o.hasNext() || x.hasNext()) {
            var n = 0
            while (n < ownPerOther && o.hasNext()) { out += o.next(); n++ }
            if (x.hasNext()) out += x.next()
            // Nothing of theirs left: what is close takes the rest.
            if (!o.hasNext()) while (x.hasNext()) out += x.next()
        }
        return out
    }
}
