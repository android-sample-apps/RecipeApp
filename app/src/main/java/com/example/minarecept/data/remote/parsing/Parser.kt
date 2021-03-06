package com.example.minarecept.data.remote.parsing


import com.example.minarecept.data.local.Recipe
import com.example.minarecept.data.local.RecipeState
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Node
import org.jsoup.select.Elements
import java.lang.IllegalStateException
import java.time.OffsetDateTime
import kotlin.streams.toList


fun parseUrl(url: String): Recipe {
    val document = Jsoup.connect(url).get()

    val images = ParseImage().getImages(document)

    var state = RecipeState.JSONLD

    val jsonBlob = try {
        extractJsonLdParts(document)
    } catch (e: Exception) {
        state = RecipeState.TRAVERSE
        JsonObject()
    }

    return getRecipe(url, jsonBlob, document, images[0], state)
}


fun extractJsonLdParts(document: Document): JsonObject {
    val elements: Elements = document.select("script[type='application/ld+json'], script[type='application/javascript']")
    lateinit var obj: JsonObject

    elements.forEach {
        val jsonElem = JsonParser.parseString(it.data())

        // Get the type of script tag (looking for @type: Recipe)
        val type = try {
            when (JSONTokener(it.data()).nextValue()) {
                is JSONObject -> jsonElem.asJsonObject.get("@type").toString()
                is JSONArray -> jsonElem.asJsonArray.last().asJsonObject.get("@type").toString() //TODO: Don't just get the last one
                else -> "Something weird"
            }
        } catch (e: Exception) {
            "Error"
        }

        // if type is Recipe then get the JsonObject it contains
        if (type.contains("Recipe")) {
            obj = when(JSONTokener(it.data()).nextValue()) {
                is JSONObject -> jsonElem.asJsonObject
                is JSONArray -> jsonElem.asJsonArray.last().asJsonObject //TODO: Don't just get the last one
                else -> jsonElem.asJsonObject
            }
        }
    }

    return obj
}

private fun getRecipe(
    url: String,
    jsonLd: JsonObject,
    document: Document,
    img: String,
    state: RecipeState
): Recipe {
    val title = ParseTitle().getTitle(jsonLd, document)
    val description = ParseDescription().getDescription(jsonLd, document)
    val ingredients = ParseIngredients().getIngredients(jsonLd, document)
    val instructions = ParseInstructions().getInstructions(jsonLd, document, ingredients)
    val time = ParseTime().getTime(jsonLd)
    val recipeYield = ParseYield().getYield(jsonLd)

    val recipeState = if (ingredients.isNullOrEmpty() && instructions.isNullOrEmpty()) {
        RecipeState.WEBVIEW
    } else {
        state
    }

    return Recipe(
        recipeState = recipeState,
        title = title,
        description = description,
        thumbnailImage = img,
        recipeUrl = url,
        ingredients = ingredients,
        instructions = instructions,
        isFavorite = false,
        yield = recipeYield,
        totalTime = time,
        published = OffsetDateTime.now(),
        lastUpdated = OffsetDateTime.now()
    )


}

fun findTwoUniqueEntries(map: MutableMap<Node, Double>): Pair<Node, Node> {
    /**
     * Returns the two highest scoring and unique nodes (based on their content).
     */
    val uniqueKeys = map.keys.distinctBy { Jsoup.parse(it.outerHtml()).body().text() }
    val mapWithUniqueKeys = map.filterKeys { it in uniqueKeys }
    val (_, secondHighestScore) = findTwoMaxNumbers(mapWithUniqueKeys.map { it.value })
    val filteredMap = mapWithUniqueKeys
        .filterValues { it >= secondHighestScore }.entries.stream().toList().take(2)

    return Pair(filteredMap[0].key, filteredMap[1].key)
}

private fun findTwoMaxNumbers(listOfDouble: List<Double>): Pair<Double, Double> {
    /**
     * Returns the two largest Doubles from a list of Doubles.
     */
    var maxOne: Double = 0.0
    var maxTwo: Double = 0.0
    for (n in listOfDouble) {
        if (maxOne < n) {
            maxTwo = maxOne
            maxOne = n
        } else if (maxTwo < n) {
            maxTwo = n
        }
    }
    return Pair(maxOne, maxTwo)
}

private fun checkNotListElement(node: Node): Node {
    /**
     * Checks that the Lca node is not part of a list - because then we want the whole list.
     * If it is just part of a list then we recurse call this function with the parent node.
     */
    val tag = node.nodeName()
    val illegalNodeNames = listOf<String>("li", "tr", "td", "p", "span")
    return if (tag in illegalNodeNames) {
        checkNotListElement(node.parent())
    } else {
        node
    }
}

fun lowestCommonAncestor(node1: Node?, node2: Node?): Node {
    /**
     * Returns the lowest common ancestor of node1 and node2
     */
    var ancestor: Node? = node1
    while (ancestor != null) {
        if (isAncestor(ancestor, node2)) {
            return checkNotListElement(ancestor)
        }
        ancestor = ancestor.parent()
    }
    throw IllegalStateException("node1 and node2 do not have common ancestor")
}

private fun isAncestor(node1: Node?, node2: Node?): Boolean {
    /**
     * Returns true if node1 is ancestor of node2 or node1 == node2
     */
    if (node1 === node2) {
        return true
    }
    var ancestor: Node? = node2

    while (ancestor != null) {
        if (ancestor === node1) {
            return true
        }
        ancestor = ancestor.parent()
    }

    return false
}