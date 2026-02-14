package work.art1st.mineRails.core.api.util

import work.art1st.mineRails.core.api.render.Asset

interface AssetStore {
    fun getAsset(id: String) : Asset
}