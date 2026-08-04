package com.bseblueprint.screener.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PattasManageActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var input: TextInputEditText
    private lateinit var adapter: PattasManageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattas_manage)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.manageRecycler)
        input = findViewById(R.id.inputSymbol)
        adapter = PattasManageAdapter { symbol -> removeSymbol(symbol) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { addSymbol() }
        refreshList()
    }

    private fun addSymbol() {
        val symbol = input.text?.toString()?.trim()?.uppercase().orEmpty()
        if (symbol.isEmpty()) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PythonBridge.addPattasSymbol(symbol)
                }
                input.text?.clear()
                refreshList()
            } catch (t: Throwable) {
                Toast.makeText(this@PattasManageActivity, t.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeSymbol(symbol: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PythonBridge.removePattasSymbol(symbol)
                }
                refreshList()
            } catch (t: Throwable) {
                Toast.makeText(this@PattasManageActivity, t.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshList() {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { PythonBridge.getPattasSymbolsJson() }
                val arr: JsonArray? = JsonSafe.arr(json, "symbols")
                val items = PattasJsonParser.parseSymbols(arr).map { it.symbol to it.name }
                adapter.submit(items)
            } catch (t: Throwable) {
                Toast.makeText(this@PattasManageActivity, t.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
