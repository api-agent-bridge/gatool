/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatool.boot.autoconfigure;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

/**
 * Decides which folder a cache uses: the directory the operator configured, as it is, or
 * the default under the JVM's temporary directory once it has passed a check.
 *
 * <p>
 * The temporary directory of a Linux host is one directory that every account writes to.
 * A folder inside it belongs to whichever account made it first, so another account can
 * make GATool's folder ahead of GATool and put a schema into it. GATool would then read
 * that schema on the day the registry is down, and also on a day the registry is up,
 * because a registry confirms a copy by its ETag and answers 304 to a copy that carries
 * the ETag it serves. The descriptions of that schema reach the model as tool text.
 *
 * <p>
 * So the default folder is made for its owner alone, and it is used only while the
 * account GATool runs as owns it and group and others cannot write to it. The folder that
 * carries the account's name and the cache folder inside it are both checked. A folder
 * that fails is left unread and unwritten, startup warns with the reason, and the
 * application runs as it does with the cache switched off.
 *
 * <p>
 * A directory the operator configured is used without the check. It is the operator's to
 * secure, and a volume mounted into a container is commonly owned by root and writable by
 * a group the process belongs to, which the check would refuse. Refusing every folder
 * that fails, configured or default, would stop the persistent volume the documentation
 * recommends. A configured value that names the default folder itself is checked, because
 * it is the same folder in the same shared directory.
 *
 * @author Željko Kozina
 */
final class CacheDirectories {

	private static final Log logger = LogFactory.getLog(CacheDirectories.class);

	private static final FileAttribute<Set<PosixFilePermission>> FOR_THE_OWNER_ALONE = PosixFilePermissions
		.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

	private CacheDirectories() {
	}

	/**
	 * Returns the folder one cache uses.
	 * @param configured the value of the cache's property, which is the default where the
	 * application leaves the property unset
	 * @param defaultDirectory the default of that property, a cache folder inside the
	 * account's folder under the temporary directory
	 * @param property the name of the property, which the warning names
	 * @param withoutTheCache what the application does while the cache is unused, which
	 * the warning says
	 * @return the folder, or {@code null} where the property is blank or the default
	 * folder failed the check
	 */
	static @Nullable Path resolve(String configured, String defaultDirectory, String property, String withoutTheCache) {
		if (configured.isBlank()) {
			return null;
		}
		Path directory = Path.of(configured);
		Path defaultFolder = Path.of(defaultDirectory);
		if (!directory.toAbsolutePath().normalize().equals(defaultFolder.toAbsolutePath().normalize())) {
			return directory;
		}
		Path accountFolder = defaultFolder.getParent();
		Refusal refusal = (accountFolder != null) ? prepare(accountFolder) : null;
		if (refusal == null) {
			refusal = prepare(defaultFolder);
		}
		if (refusal == null) {
			return directory;
		}
		logger.warn("GATool leaves the cache folder " + refusal.folder() + " unused, because " + refusal.reason()
				+ ". The folder sits under the JVM's temporary directory, where another account on this host "
				+ "can make it first, so GATool uses it only while the account it runs as owns it and group and "
				+ "others cannot write to it. Until then " + withoutTheCache + ". Set " + property
				+ " to a directory of your own, which GATool uses as it is.");
		return null;
	}

	// Makes one folder for its owner alone where it is missing, and checks it either way.
	//
	// Returns the folder and the reason it is left unused, or null where it passed.
	//
	// createDirectory answers FileAlreadyExistsException where anything holds the name, a
	// symbolic link included, so a folder another account makes between the check and the
	// creation cannot slip in: either this call made the folder, or the check below reads
	// the one that was there. createDirectories would follow a link and return without a
	// word. A folder this call made is checked as well, which costs one read and keeps a
	// single path through the method.
	private static @Nullable Refusal prepare(Path folder) {
		try {
			Path parent = folder.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (parent != null && Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
				Files.createDirectory(folder, FOR_THE_OWNER_ALONE);
			}
			else {
				Files.createDirectory(folder);
			}
		}
		catch (FileAlreadyExistsException ex) {
			// The check below reads what holds the name.
		}
		catch (IOException | UnsupportedOperationException | SecurityException ex) {
			return new Refusal(folder, "it could not be made: " + ex);
		}
		try {
			String reason = findReasonInTheFolderItself(folder);
			if (reason == null) {
				reason = findReasonInItsOwner(folder, accountWritingInto(folder));
			}
			return (reason != null) ? new Refusal(folder, reason) : null;
		}
		catch (IOException | UnsupportedOperationException | SecurityException ex) {
			return new Refusal(folder, "its owner and permissions could not be read: " + ex);
		}
	}

	/**
	 * Returns why a folder under the temporary directory is left unused, or {@code null}
	 * where it is safe to use.
	 * @param folder the folder to check, which exists
	 * @param account the account GATool runs as
	 * @return the reason, written to follow "because", or {@code null}
	 * @throws IOException if the folder's attributes cannot be read
	 */
	static @Nullable String findReasonToLeaveUnused(Path folder, UserPrincipal account) throws IOException {
		String reason = findReasonInTheFolderItself(folder);
		return (reason != null) ? reason : findReasonInItsOwner(folder, account);
	}

	// The link itself is read, and its target stays unread. A link another account
	// made can point at a folder this account owns, such as its home directory, and
	// a check on the target would pass while GATool wrote its cache there.
	//
	// The permission bits are read where the file system keeps them. On Windows it
	// keeps an access control list in their place, the temporary directory there
	// already belongs to one account, and the owner check alone is what remains. A
	// folder writable by its group is refused along with one writable by everyone,
	// because GATool cannot tell who belongs to the group, and the folder it makes
	// itself is closed to both.
	private static @Nullable String findReasonInTheFolderItself(Path folder) throws IOException {
		if (Files.isSymbolicLink(folder)) {
			return "it is a symbolic link";
		}
		if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
			return "it is a file";
		}
		PosixFileAttributeView posix = Files.getFileAttributeView(folder, PosixFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
			if (permissions.contains(PosixFilePermission.GROUP_WRITE)
					|| permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
				return "its permissions, " + PosixFilePermissions.toString(permissions)
						+ ", let other accounts write to it";
			}
		}
		return null;
	}

	private static @Nullable String findReasonInItsOwner(Path folder, UserPrincipal account) throws IOException {
		UserPrincipal owner = Files.getOwner(folder, LinkOption.NOFOLLOW_LINKS);
		if (!owner.equals(account)) {
			return "the account " + owner.getName() + " owns it, and GATool runs as " + account.getName();
		}
		return null;
	}

	// Returns the account that owns a file GATool writes into the folder, which is the
	// account GATool runs as.
	//
	// The operating system is asked through a file, because Java offers the account
	// two other ways and both can be wrong. The user.name property is a name anyone
	// starting the JVM can set, and it reads "?" for a user id without an entry in the
	// password file, which is how a container with an arbitrary user id runs. Looking
	// that name up needs the entry as well. The owner of a file this process has just
	// made is the account the operating system writes as, whatever the name says.
	//
	// The file is made inside the folder being checked, which by then is a real
	// directory closed to group and others, so GATool leaves the shared temporary
	// directory itself untouched. A folder GATool cannot write into fails here, and a
	// cache there could not be written either.
	private static UserPrincipal accountWritingInto(Path folder) throws IOException {
		Path probe = Files.createTempFile(folder, "owner", ".probe");
		try {
			return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
		}
		finally {
			Files.deleteIfExists(probe);
		}
	}

	private record Refusal(Path folder, String reason) {
	}

}
