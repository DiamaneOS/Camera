package app.grapheneos.camera;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.UUID;

/** Debug-only synthetic storage; standard MANAGE_DOCUMENTS and scoped URI gates apply. */
public final class RecoveryDocumentsProvider extends DocumentsProvider {
    private static final String[] DOCUMENT_COLUMNS={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE};
    private File root;
    @Override public boolean onCreate() {
        root=new File(getContext().getNoBackupFilesDir(),"recovery-test-provider");
        return root.isDirectory() || root.mkdirs();
    }
    private String fault() {
        try {File f=new File(root,"fault");return f.length()<=64 ? new String(Files.readAllBytes(f.toPath())).trim() : "";}
        catch(Exception ignored) {return "";}
    }
    private void fault(String value) {
        try {Files.write(new File(root,"fault").toPath(),value.getBytes());}
        catch(Exception error) {throw new IllegalStateException(error);}
    }
    private File file(String id) throws FileNotFoundException {
        if(!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new FileNotFoundException("Invalid test document");
        return new File(root,id+".jpg");
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] cols=projection!=null ? projection : new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS};
        MatrixCursor cursor=new MatrixCursor(cols);
        if(!new File(root,"enabled").isFile())return cursor;
        MatrixCursor.RowBuilder row=cursor.newRow();
        for(String col:cols) {
            if(col.equals(Root.COLUMN_ROOT_ID) || col.equals(Root.COLUMN_DOCUMENT_ID))row.add("root");
            else if(col.equals(Root.COLUMN_TITLE))row.add("Synthetic recovery checks");
            else if(col.equals(Root.COLUMN_FLAGS))row.add(Root.FLAG_SUPPORTS_CREATE|Root.FLAG_SUPPORTS_IS_CHILD);
            else row.add(null);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        if(fault().equals("query-unavailable"))throw new SecurityException("Synthetic provider unavailable");
        if(fault().equals("query-null"))return null;
        MatrixCursor cursor=new MatrixCursor(projection!=null ? projection : DOCUMENT_COLUMNS);
        if(id.equals("root")) {
            row(cursor,"root","Recovery checks",Document.MIME_TYPE_DIR,Document.FLAG_DIR_SUPPORTS_CREATE,0);
        } else {
            File f=file(id);
            if(f.isFile()) {
                String name;
                try {name=new String(Files.readAllBytes(new File(root,id+".name").toPath()));}
                catch(Exception ignored) {name=id+".jpg";}
                row(cursor,id,name,"image/jpeg",Document.FLAG_SUPPORTS_WRITE|Document.FLAG_SUPPORTS_DELETE,f.length());
            }
        }
        return cursor;
    }
    private void row(MatrixCursor cursor,String id,String name,String mime,int flags,long bytes) {
        MatrixCursor.RowBuilder row=cursor.newRow();
        for(String col:cursor.getColumnNames()) {
            if(col.equals(Document.COLUMN_DOCUMENT_ID))row.add(id);
            else if(col.equals(Document.COLUMN_DISPLAY_NAME))row.add(name);
            else if(col.equals(Document.COLUMN_MIME_TYPE))row.add(mime);
            else if(col.equals(Document.COLUMN_FLAGS))row.add(flags);
            else if(col.equals(Document.COLUMN_SIZE))row.add(bytes);
            else row.add(null);
        }
    }
    @Override public boolean isChildDocument(String parent,String child) {
        // Every UUID in this synthetic namespace belongs to its sole root,
        // including missing IDs whose query must return an empty cursor.
        return parent.equals("root") && child.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort) throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException("No test parent");
        MatrixCursor cursor=new MatrixCursor(projection!=null ? projection : DOCUMENT_COLUMNS);
        File[] files=root.listFiles((dir,name)->name.endsWith(".jpg"));
        if(files!=null)for(File file:files) {
            String id=file.getName().substring(0,file.getName().length()-4);
            row(cursor,id,id+".jpg","image/jpeg",Document.FLAG_SUPPORTS_DELETE|Document.FLAG_SUPPORTS_WRITE,file.length());
        }
        return cursor;
    }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        if(!parent.equals("root") || !mime.equals("image/jpeg") || name.length()>128 || name.contains("/"))throw new FileNotFoundException("Invalid test creation");
        String id=UUID.randomUUID().toString();File file=file(id);
        try {if(!file.createNewFile())throw new IllegalStateException();Files.write(new File(root,id+".name").toPath(),name.getBytes());}
        catch(Exception error) {throw new FileNotFoundException("Cannot create synthetic document");}
        return id;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(mode.equals("r") && fault().equals("read-unavailable"))throw new FileNotFoundException("Synthetic read unavailable");
        if(mode.equals("r") && fault().equals("block-read")) {
            try {Files.write(new File(root,"read-blocked").toPath(),id.getBytes());Thread.sleep(5_000);}
            catch(Exception error) {throw new FileNotFoundException("Synthetic read interrupted");}
        }
        if(mode.contains("w") && fault().equals("block-write")) {
            try {Files.write(new File(root,"write-blocked").toPath(),id.getBytes());Thread.sleep(20_000);}
            catch(Exception error) {throw new FileNotFoundException("Synthetic write interrupted");}
        }
        return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if(fault().equals("delete-unavailable"))throw new FileNotFoundException("Synthetic deletion unavailable");
        File file=file(id);if(!file.delete())throw new FileNotFoundException("Synthetic document not deleted");
        new File(root,id+".name").delete();
        if(fault().equals("delete-then-unavailable"))fault("query-unavailable");
    }
}
