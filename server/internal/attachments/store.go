package attachments

import (
	"context"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

// BlobStore keeps attachment bytes. The local directory store is the only
// implementation in this phase.
type BlobStore interface {
	// Put writes r under id and returns the number of bytes written. On any
	// error nothing is left behind.
	Put(ctx context.Context, id string, r io.Reader) (int64, error)
	// Open returns the blob; a missing one gives an error matching fs.ErrNotExist.
	Open(ctx context.Context, id string) (io.ReadSeekCloser, error)
	// Delete removes the blob; a missing one is not an error.
	Delete(ctx context.Context, id string) error
}

type dirStore struct{ root string }

// NewDirStore returns a BlobStore that writes root/attachments/<id[0:2]>/<id>.
// ids must be valid attachment IDs (32 lowercase hex characters).
func NewDirStore(root string) BlobStore { return dirStore{root: root} }

func (s dirStore) path(id string) (dir, file string) {
	dir = filepath.Join(s.root, "attachments", id[:2])
	return dir, filepath.Join(dir, id)
}

func (s dirStore) Put(_ context.Context, id string, r io.Reader) (int64, error) {
	if !validID(id) {
		return 0, errors.New("attachments: bad id")
	}
	dir, file := s.path(id)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return 0, err
	}
	tmp, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return 0, err
	}
	n, err := io.Copy(tmp, r)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp.Name(), file)
	}
	if err != nil {
		os.Remove(tmp.Name())
		return 0, err
	}
	return n, nil
}

func (s dirStore) Open(_ context.Context, id string) (io.ReadSeekCloser, error) {
	if !validID(id) {
		return nil, fs.ErrNotExist
	}
	_, file := s.path(id)
	return os.Open(file)
}

func (s dirStore) Delete(_ context.Context, id string) error {
	if !validID(id) {
		return nil
	}
	_, file := s.path(id)
	if err := os.Remove(file); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// validID reports whether id is 32 lowercase hex characters.
func validID(id string) bool {
	if len(id) != 32 {
		return false
	}
	for i := 0; i < len(id); i++ {
		if c := id[i]; (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}
