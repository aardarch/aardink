/*
 * Copyright 2026 Aardarch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aardarch.aardink.sample.samples

/**
 * The two versions of one Kotlin file the diff editor page compares: a changed line, an insertion, a
 * block of lines replaced by fewer, and a change of spacing inside a line, with runs of unchanged
 * lines between them long enough for "hide unchanged regions" to fold.
 */
object DiffSampleAssets {

    /** The file before the edit. */
    val ORIGINAL: String = """
        package com.example.cart

        import kotlin.math.round

        /** A shopping cart that totals its lines. */
        class Cart {
            private val lines = mutableListOf<CartLine>()

            fun add(item: Item, quantity: Int = 1) {
                lines += CartLine(item, quantity)
            }

            fun remove(item: Item) {
                lines.removeAll { it.item == item }
            }

            fun count(): Int {
                var count = 0
                for (line in lines) {
                    count += line.quantity
                }
                return count
            }

            fun clear() {
                lines.clear()
            }

            fun total(): Double {
                var sum = 0.0
                for (line in lines) {
                    sum += line.item.price * line.quantity
                }
                return round(sum * 100) / 100
            }

            fun describe(): String {
                val out = StringBuilder()
                for (line in lines) {
                    out.append(line.quantity)
                    out.append(" x ")
                    out.append(line.item.name)
                    out.append('\n')
                }
                return out.toString()
            }
        }

        data class Item(val name: String, val price: Double)

        data class CartLine(val item: Item, val quantity: Int)
    """.trimIndent()

    /** The file after it. */
    val MODIFIED: String = """
        package com.example.cart

        import kotlin.math.round

        /** A shopping cart that totals its lines. */
        class Cart {
            private val lines = mutableListOf<CartLine>()

            fun add(item: Item, quantity: Int = 1) {
                require(quantity > 0) { "quantity must be positive" }
                lines += CartLine(item, quantity)
            }

            fun remove(item: Item) {
                lines.removeAll { it.item == item }
            }

            fun isEmpty(): Boolean = lines.isEmpty()

            fun count(): Int {
                var count = 0
                for (line in lines) {
                    count += line.quantity
                }
                return count
            }

            fun clear() {
                lines.clear()
            }

            fun total(): Double {
                var sum = 0.0
                for (line in lines) {
                    sum += line.subtotal()
                }
                return round(sum * 100) / 100
            }

            fun describe(): String =
                lines.joinToString("\n") { it.quantity.toString() + " x " + it.item.name }
        }

        data class Item(val name:   String, val price: Double)

        data class CartLine(val item: Item, val quantity: Int) {
            fun subtotal(): Double = item.price * quantity
        }
    """.trimIndent()
}
