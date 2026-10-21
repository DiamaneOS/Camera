package app.grapheneos.camera.capturer

/** MediaStore table aliases name one row within one actual storage volume. */
object MediaPhotoAliases {
    fun forRow(volume: String,row: String): List<String> {
        if(!volume.matches(Regex("[A-Za-z0-9_-]{1,80}")) || !row.matches(Regex("[0-9]{1,19}")) || row.toLongOrNull()==null) return emptyList()
        return listOf("content://media/$volume/images/media/$row","content://media/$volume/file/$row")
    }
}
