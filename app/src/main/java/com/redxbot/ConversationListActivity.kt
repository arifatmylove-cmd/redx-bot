package com.redxbot

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.redxbot.adapter.ConversationAdapter
import com.redxbot.databinding.ActivityConversationListBinding
import com.redxbot.storage.ConversationStorage

class ConversationListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationListBinding
    private lateinit var storage: ConversationStorage
    private lateinit var adapter: ConversationAdapter

    companion object {
        private const val MENU_BUILDER  = 2001
        private const val MENU_SETTINGS = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "RedxBot"

        storage = ConversationStorage(this)
        adapter = ConversationAdapter(
            onOpen   = { id -> startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONV_ID, id)) },
            onDelete = { id -> storage.deleteConversation(id); refreshList() }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabNewChat.setOnClickListener {
            val id = storage.createConversation()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONV_ID, id))
        }
    }

    override fun onResume() { super.onResume(); refreshList() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_BUILDER,  0, "🔨 Build App")
        menu.add(0, MENU_SETTINGS, 1, "⚙️ Settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        MENU_BUILDER  -> { startActivity(Intent(this, AppBuilderActivity::class.java)); true }
        MENU_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java));  true }
        else          -> super.onOptionsItemSelected(item)
    }

    private fun refreshList() {
        val list = storage.listConversations()
        adapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}
