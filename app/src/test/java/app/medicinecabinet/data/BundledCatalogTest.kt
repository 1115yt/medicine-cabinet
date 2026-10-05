package app.medicinecabinet.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.ProductLookupResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestCabinetApplication::class)
class BundledCatalogTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun `provided catalog record is available offline with normalized barcode`() = runBlocking {
        val catalog = BundledCatalog(context.assets)
        val short = catalog.lookup("6902401045076")
        assertEquals(1, short.size)
        assertEquals("50mg*10片", short.single().specification)
        assertEquals(short, catalog.lookup("06902401045076"))
        assertTrue(catalog.lookup("not-a-barcode").isEmpty())
    }
    @Test fun `catalog manifests match verified source counts and small compressed size`() {
        val manifest = context.assets.open("medicine-catalog/manifest.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(47737, manifest.getValue("sourceRows").jsonPrimitive.int)
        assertEquals(47256, manifest.getValue("barcodeCount").jsonPrimitive.int)
        assertTrue(manifest.getValue("compressedBytes").jsonPrimitive.int < 2 * 1024 * 1024)
        assertEquals(100, context.assets.list("medicine-catalog")!!.count { Regex("[0-9]{2}\\.json").matches(it) })
    }
    @Test fun `local matches do not spend configured network quota`() = runBlocking {
        val preferences = LookupPreferences(context)
        preferences.saveAppCode("fixture-only-appcode")
        preferences.saveMxnzp("fixture-id", "fixture-secret")
        val forbidden = BarcodeTransport { _, _ -> fail("内置库已命中，不应联网"); BarcodeHttpResponse(500) }
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, AliBarcodeClient(forbidden), MxnzpBarcodeClient(forbidden))
        assertTrue(lookup.lookup("06902401045076") is ProductLookupResult.Found)
        assertTrue(lookup.lookup("https://example.com/trace") is ProductLookupResult.Manual)
    }
    @Test fun `MXNZP failure falls through to enabled Ali supplement`() = runBlocking {
        val preferences = LookupPreferences(context)
        preferences.saveAppCode("fixture-only-appcode")
        preferences.saveMxnzp("fixture-id", "fixture-secret")
        var mxnzpCalls = 0
        var aliCalls = 0
        val mxnzp = MxnzpBarcodeClient { _, _ -> mxnzpCalls++; BarcodeHttpResponse(200, """{"code":10036}""") }
        val ali = AliBarcodeClient { _, _ ->
            aliCalls++
            BarcodeHttpResponse(200, """{"showapi_res_code":0,"showapi_res_body":{"ret_code":0,"code":"4006381333931","goodsName":"资料示例"}}""")
        }
        val result = ProductLookup(BundledCatalog(context.assets), preferences, ali, mxnzp).lookup("4006381333931")
        assertTrue(result is ProductLookupResult.Found)
        assertEquals(1, mxnzpCalls)
        assertEquals(1, aliCalls)
    }
    @Test fun `disabled supplements never call network and settings expose no credentials`() = runBlocking {
        val preferences = LookupPreferences(context)
        preferences.saveAppCode("fixture-only-appcode")
        preferences.saveMxnzp("fixture-id", "fixture-secret")
        preferences.setEnabled(false)
        preferences.setMxnzpEnabled(false)
        assertFalse(preferences.settings.value.toString().contains("fixture"))
        val forbidden = BarcodeTransport { _, _ -> fail("查询已关闭，不应联网"); BarcodeHttpResponse(500) }
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, AliBarcodeClient(forbidden), MxnzpBarcodeClient(forbidden))
        assertTrue(lookup.lookup("4006381333931") is ProductLookupResult.Manual)
    }
}
